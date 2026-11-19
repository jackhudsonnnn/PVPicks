package com.p2picks.app.security;

import com.p2picks.app.common.PostgresText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Limits failed sign-ins in Redis, in fixed windows: passwords per account name and per client address, and sign-in
 * codes per player (or unknown name) with a generous per-address backstop. Only failures count, so people who sign in
 * correctly are never slowed down. Once a counter reaches its limit, sign-in is refused (429) until the window ends,
 * even with the right password or code; a success clears that account's or player's counter.
 *
 * <p>Redis is disposable (ADR 0003): when it is unreachable the throttle fails open and sign-in still works against
 * PostgreSQL, with BCrypt as the remaining brake. Keys hold a SHA-256 of the name or address, never the plain value.
 */
@Component
class LoginThrottle {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    /** Increments the counter and starts its window on the first failure, atomically, so no key outlives it. */
    private static final RedisScript<Long> RECORD = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private static final String CODE_PREFIX = "login-fail:code:";
    private static final String PLAYER_PREFIX = CODE_PREFIX + "player:";
    private static final String NAME_PREFIX = CODE_PREFIX + "name:";
    private static final String ADDRESS_PREFIX = CODE_PREFIX + "address:";

    private final StringRedisTemplate redis;
    private final int perAccount;
    private final int perAddress;
    private final int codesPerPlayer;
    private final int codesPerAddress;
    private final Duration window;

    LoginThrottle(StringRedisTemplate redis,
            @Value("${pvpicks.login-throttle.per-account:10}") int perAccount,
            @Value("${pvpicks.login-throttle.per-address:50}") int perAddress,
            @Value("${pvpicks.login-throttle.codes-per-player:10}") int codesPerPlayer,
            @Value("${pvpicks.login-throttle.codes-per-address:100}") int codesPerAddress,
            @Value("${pvpicks.login-throttle.window:PT15M}") Duration window) {
        this.codesPerPlayer = codesPerPlayer;
        this.codesPerAddress = codesPerAddress;
        this.redis = redis;
        this.perAccount = perAccount;
        this.perAddress = perAddress;
        this.window = window;
    }

    Duration window() {
        return window;
    }

    /** True when the account name or the address has used up its failures for the current window. */
    boolean blocked(String username, String address) {
        try {
            List<String> counts = redis.opsForValue().multiGet(List.of(accountKey(username), addressKey(address)));
            return counts != null && (atLeast(counts.get(0), perAccount) || atLeast(counts.get(1), perAddress));
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; allowing the attempt: {}", exception.getMessage());
            return false;
        }
    }

    void failed(String username, String address) {
        try {
            String ttl = Long.toString(window.toMillis());
            redis.execute(RECORD, List.of(accountKey(username)), ttl);
            redis.execute(RECORD, List.of(addressKey(address)), ttl);
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; failure not counted: {}", exception.getMessage());
        }
    }

    /**
     * Who a code attempt is for: a known player (by id, so a rename doesn't reset their count) or, when the typed
     * username matches nobody, that name, so made-up names can't be tried for free.
     */
    record CodeSubject(UUID player, String name) {
        static CodeSubject of(UUID player) {
            return new CodeSubject(player, null);
        }

        static CodeSubject named(String name) {
            return new CodeSubject(null, PostgresText.trim(name == null ? "" : name).toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Whether this player (or unknown name) has used up its wrong codes, or the address its backstop. Codes are short
     * (4 characters) but must match the named player's own code, so 10 per player per 15 minutes leaves a guesser
     * centuries from one code. Behind Docker Desktop every phone shares one address, so its limit is generous.
     */
    boolean blockedCode(CodeSubject subject, String address) {
        try {
            List<String> counts = redis.opsForValue().multiGet(List.of(codeKey(subject), codeAddressKey(address)));
            return counts != null && (atLeast(counts.get(0), codesPerPlayer)
                    || atLeast(counts.get(1), codesPerAddress));
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; allowing the attempt: {}", exception.getMessage());
            return false;
        }
    }

    void failedCode(CodeSubject subject, String address) {
        try {
            String ttl = Long.toString(window.toMillis());
            redis.execute(RECORD, List.of(codeKey(subject)), ttl);
            redis.execute(RECORD, List.of(codeAddressKey(address)), ttl);
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; failure not counted: {}", exception.getMessage());
        }
    }

    /** A right code clears the player's count (not the address's, which other guests share). */
    void codeSucceeded(UUID player) {
        try {
            redis.delete(codeKey(CodeSubject.of(player)));
        } catch (DataAccessException exception) {
            log.debug("Login throttle unavailable; code counter not cleared", exception);
        }
    }

    void succeeded(String username) {
        try {
            redis.delete(accountKey(username));
        } catch (DataAccessException exception) {
            log.debug("Login throttle unavailable; account counter not cleared", exception);
        }
    }

    /** One wrong-code counter: its count, whether it has reached its limit, and when it resets. */
    record CodeCounter(int wrongCodes, boolean locked, Instant resetsAt) {}

    /** A player's counter, for the admin. */
    record PlayerCounter(UUID player, CodeCounter counter) {}

    record CodeLockouts(int playerLimit, int addressLimit, CodeCounter address, List<PlayerCounter> players) {}

    /** Every player with wrong codes in the current window, and the worst address counter. */
    CodeLockouts codeLockouts() {
        List<PlayerCounter> players = new ArrayList<>();
        CodeCounter worstAddress = new CodeCounter(0, false, null);
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(CODE_PREFIX + "*").count(100).build())) {
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith(PLAYER_PREFIX)) {
                    UUID player;
                    try {
                        player = UUID.fromString(key.substring(PLAYER_PREFIX.length()));
                    } catch (IllegalArgumentException notOurs) {
                        continue;
                    }
                    CodeCounter counter = counter(key, codesPerPlayer);
                    if (counter.wrongCodes() > 0) players.add(new PlayerCounter(player, counter));
                } else if (key.startsWith(ADDRESS_PREFIX)) {
                    CodeCounter counter = counter(key, codesPerAddress);
                    if (counter.wrongCodes() > worstAddress.wrongCodes()) worstAddress = counter;
                }
            }
        }
        players.sort(Comparator.comparing((PlayerCounter p) -> !p.counter().locked())
                .thenComparing(p -> -p.counter().wrongCodes()));
        return new CodeLockouts(codesPerPlayer, codesPerAddress, worstAddress, players);
    }

    private CodeCounter counter(String key, int limit) {
        String value = redis.opsForValue().get(key);
        int count = value == null ? 0 : Integer.parseInt(value);
        Long ttl = redis.getExpire(key);
        Instant resets = ttl == null || ttl < 0 ? null : Instant.now().plusSeconds(ttl);
        return new CodeCounter(count, count >= limit, resets);
    }

    /** Forgets every wrong-code counter (players, unknown names and addresses). Returns how many were cleared. */
    int clearCodeLockouts() {
        int cleared = 0;
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(CODE_PREFIX + "*").count(100).build())) {
            while (keys.hasNext()) {
                if (Boolean.TRUE.equals(redis.delete(keys.next()))) cleared++;
            }
        }
        return cleared;
    }

    /** Forgets one player's wrong codes, so they can sign in at once. */
    void clearCodeLockout(UUID player) {
        redis.delete(codeKey(CodeSubject.of(player)));
    }

    private static boolean atLeast(String count, int limit) {
        return count != null && Long.parseLong(count) >= limit;
    }

    private static String accountKey(String username) {
        // Same normalization as sign-in, so "Alice " and "alice" share one counter.
        return "login-fail:account:" + sha256(PostgresText.trim(username).toLowerCase(Locale.ROOT));
    }

    /** Player keys hold the id in plain (admins list them); names and addresses are hashed like the others. */
    private static String codeKey(CodeSubject subject) {
        return subject.player() != null ? PLAYER_PREFIX + subject.player() : NAME_PREFIX + sha256(subject.name());
    }

    private static String codeAddressKey(String address) {
        return ADDRESS_PREFIX + sha256(address == null ? "" : address);
    }

    private static String addressKey(String address) {
        return "login-fail:address:" + sha256(address == null ? "" : address);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

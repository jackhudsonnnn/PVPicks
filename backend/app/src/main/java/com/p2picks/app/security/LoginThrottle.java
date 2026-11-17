package com.p2picks.app.security;

import com.p2picks.app.common.PostgresText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Limits failed password sign-ins in Redis, per account name and per client address, in fixed windows. Only failures
 * count, so people who sign in correctly are never slowed down. Once either counter reaches its limit, sign-in is
 * refused (429) until the window ends, even with the right password; a success clears the account-name counter.
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

    private final StringRedisTemplate redis;
    private final int perAccount;
    private final int perAddress;
    private final Duration window;

    LoginThrottle(StringRedisTemplate redis,
            @Value("${pvpicks.login-throttle.per-account:10}") int perAccount,
            @Value("${pvpicks.login-throttle.per-address:50}") int perAddress,
            @Value("${pvpicks.login-throttle.window:PT15M}") Duration window) {
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

    /** Whether the address alone has used up its failures (for setup codes, which name no account). */
    boolean blockedAddress(String address) {
        try {
            String count = redis.opsForValue().get(addressKey(address));
            return atLeast(count, perAddress);
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; allowing the attempt: {}", exception.getMessage());
            return false;
        }
    }

    void failedAddress(String address) {
        try {
            redis.execute(RECORD, List.of(addressKey(address)), Long.toString(window.toMillis()));
        } catch (DataAccessException exception) {
            log.warn("Login throttle unavailable; failure not counted: {}", exception.getMessage());
        }
    }

    void succeeded(String username) {
        try {
            redis.delete(accountKey(username));
        } catch (DataAccessException exception) {
            log.debug("Login throttle unavailable; account counter not cleared", exception);
        }
    }

    private static boolean atLeast(String count, int limit) {
        return count != null && Long.parseLong(count) >= limit;
    }

    private static String accountKey(String username) {
        // Same normalization as sign-in, so "Alice " and "alice" share one counter.
        return "login-fail:account:" + sha256(PostgresText.trim(username).toLowerCase(Locale.ROOT));
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

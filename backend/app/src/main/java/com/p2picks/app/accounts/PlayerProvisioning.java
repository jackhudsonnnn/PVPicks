package com.p2picks.app.accounts;

import com.p2picks.app.common.PageCursor;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.AccountSetupCodes;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-created player accounts (LAN plan, "Accounts and passwords"). A new account has no usable password until the
 * player redeems its setup code; a forgotten password gets a reset code. There's no registration.
 */
@Service
class PlayerProvisioning {
    /** The admin's player list pages this many at a time. */
    static final int PAGE = 50;

    record Page(List<Player> items, String nextCursor) {}

    record Player(UUID id, String username, String status, boolean passwordSet, OffsetDateTime codeExpiresAt,
                  OffsetDateTime createdAt) {}

    record Created(UUID id, String username, AccountSetupCodes.Issued code) {}

    sealed interface Result {
        record Done(Created created) implements Result {}

        record Code(AccountSetupCodes.Issued code) implements Result {}

        record Invalid(String message) implements Result {}

        record Taken() implements Result {}

        record NotFound() implements Result {}

        record Unauthorized() implements Result {}
    }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final AccountSetupCodes codes;

    PlayerProvisioning(JdbcTemplate jdbc, MutationAuthentication authentication, AccountSetupCodes codes) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.codes = codes;
    }

    /**
     * Players, newest first, {@link #PAGE} at a time, with whether they've set a password and when their live code
     * (if any) expires. Throws IllegalArgumentException on a bad cursor.
     */
    Page players(String cursor) {
        OffsetDateTime before = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> parts = PageCursor.decode(cursor, 2);
            before = OffsetDateTime.parse(parts.get(0));
            beforeId = UUID.fromString(parts.get(1));
        }
        List<Player> rows = jdbc.query("""
                SELECT u.id, u.username::text AS username, u.status, u.password_hash LIKE '$2%' AS password_set,
                       u.created_at,
                       (SELECT max(c.expires_at) FROM account_setup_codes c
                        WHERE c.user_id = u.id AND c.used_at IS NULL AND c.expires_at > now()) AS code_expires_at
                FROM users u WHERE u.role = 'PLAYER' AND u.status <> 'DELETED'
                  AND (?::timestamptz IS NULL OR (u.created_at, u.id) < (?::timestamptz, ?::uuid))
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT ?
                """, (rs, row) -> new Player(rs.getObject("id", UUID.class), rs.getString("username"),
                rs.getString("status"), rs.getBoolean("password_set"),
                rs.getObject("code_expires_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class)),
                before, before, beforeId, PAGE + 1);
        if (rows.size() <= PAGE) return new Page(rows, null);
        Player last = rows.get(PAGE - 1);
        return new Page(rows.subList(0, PAGE), PageCursor.encode(last.createdAt(), last.id()));
    }

    @Transactional
    Result create(Jwt jwt, String requested) {
        String username = requested == null ? "" : PostgresText.trim(requested).toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9_]{3,15}")) {
            return new Result.Invalid("Usernames are 3 to 15 letters, numbers or underscores.");
        }
        if (!authentication.lockActive(jwt)) return new Result.Unauthorized();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, ?, ?, 'PLAYER', 'ACTIVE') ON CONFLICT (username) DO NOTHING
                """, id, username, ThreadLocalRandom.current().nextInt(1, 6), AccountSetupCodes.unusablePasswordHash());
        if (inserted == 0) return new Result.Taken();
        AccountSetupCodes.Issued code = codes.issue(id, AccountSetupCodes.Purpose.SETUP, admin(jwt));
        authentication.requireUnexpired(jwt);
        return new Result.Done(new Created(id, username, code));
    }

    /** A new code for an active player: SETUP while they've never set a password, RESET after. */
    @Transactional
    Result issueCode(Jwt jwt, UUID playerId) {
        if (!authentication.lockActive(jwt)) return new Result.Unauthorized();
        List<String> hashes = jdbc.queryForList("""
                SELECT password_hash FROM users WHERE id = ? AND role = 'PLAYER' AND status = 'ACTIVE' FOR UPDATE
                """, String.class, playerId);
        if (hashes.isEmpty()) return new Result.NotFound();
        AccountSetupCodes.Purpose purpose = hashes.getFirst().startsWith("$2")
                ? AccountSetupCodes.Purpose.RESET : AccountSetupCodes.Purpose.SETUP;
        AccountSetupCodes.Issued code = codes.issue(playerId, purpose, admin(jwt));
        authentication.requireUnexpired(jwt);
        return new Result.Code(code);
    }

    private static UUID admin(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

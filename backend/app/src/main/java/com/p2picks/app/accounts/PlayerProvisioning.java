package com.p2picks.app.accounts;

import com.p2picks.app.common.AbusiveLanguage;
import com.p2picks.app.common.PageCursor;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.SignInCodes;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-created player accounts. There's no registration and players have no password: each gets a sign-in code
 * ({@link SignInCodes}) the admin shows them as a QR code, and rotates when it needs replacing.
 */
@Service
class PlayerProvisioning {
    /** The admin's player list pages this many at a time. */
    static final int PAGE = 50;

    record Page(List<Player> items, String nextCursor) {}

    record Player(UUID id, String username, Integer hue, String status, OffsetDateTime lastSignInAt,
                  OffsetDateTime codeRotatedAt, OffsetDateTime createdAt, int strikes, int openReports) {}

    record Created(UUID id, String username, String code) {}

    sealed interface Result {
        record Done(Created created) implements Result {}

        record Code(String code) implements Result {}

        record Invalid(String message) implements Result {}

        record Taken() implements Result {}

        record NotFound() implements Result {}

        record Unauthorized() implements Result {}
    }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final SignInCodes codes;
    private final ApplicationEventPublisher events;

    PlayerProvisioning(JdbcTemplate jdbc, MutationAuthentication authentication, SignInCodes codes,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.codes = codes;
        this.events = events;
    }

    /**
     * Players, newest first, {@link #PAGE} at a time, with their last sign-in, when their code was last rotated,
     * their strikes and open reports against them (never the code itself: {@link #reveal} shows one). {@code search} keeps only usernames starting
     * with it. Throws IllegalArgumentException on a bad cursor.
     */
    Page players(String cursor, String search) {
        String prefix = search == null || search.isBlank() ? null
                : search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("_", "\\_")
                        .replace("%", "\\%") + "%";
        OffsetDateTime before = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> parts = PageCursor.decode(cursor, 2);
            before = OffsetDateTime.parse(parts.get(0));
            beforeId = UUID.fromString(parts.get(1));
        }
        List<Player> rows = jdbc.query("""
                SELECT u.id, u.username::text AS username, u.hue, u.status, u.created_at,
                       (SELECT max(s.created_at) FROM auth_sessions s WHERE s.user_id = u.id) AS last_sign_in,
                       (SELECT c.rotated_at FROM sign_in_codes c WHERE c.user_id = u.id) AS code_rotated_at,
                       COALESCE((SELECT f.strike_count FROM admin_user_flags f WHERE f.user_id = u.id), 0) AS strikes,
                       (SELECT count(*) FROM reports r WHERE r.target_id = u.id AND r.status = 'OPEN') AS open_reports
                FROM users u WHERE u.role = 'PLAYER' AND u.status <> 'DELETED'
                  AND (?::text IS NULL OR u.username::text LIKE ?)
                  AND (?::timestamptz IS NULL OR (u.created_at, u.id) < (?::timestamptz, ?::uuid))
                ORDER BY u.created_at DESC, u.id DESC
                LIMIT ?
                """, (rs, row) -> new Player(rs.getObject("id", UUID.class), rs.getString("username"),
                (Integer) rs.getObject("hue", Integer.class), rs.getString("status"), rs.getObject("last_sign_in", OffsetDateTime.class),
                rs.getObject("code_rotated_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("strikes"), rs.getInt("open_reports")),
                prefix, prefix, before, before, beforeId, PAGE + 1);
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
        if (AbusiveLanguage.contains(username)) return new Result.Invalid(AbusiveLanguage.MESSAGE);
        if (!authentication.lockActive(jwt)) return new Result.Unauthorized();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, ?, ?, 'PLAYER', 'ACTIVE') ON CONFLICT (username) DO NOTHING
                """, id, username, ThreadLocalRandom.current().nextInt(1, 6), SignInCodes.NO_PASSWORD);
        if (inserted == 0) return new Result.Taken();
        String code = codes.rotate(id, admin(jwt));
        events.publishEvent(new PlayerCreated(id, username));
        authentication.requireUnexpired(jwt);
        return new Result.Done(new Created(id, username, code));
    }

    /** A new code for a player, ending all their sessions so the old code (and every device) is signed out. */
    @Transactional
    Result rotate(Jwt jwt, UUID playerId) {
        if (!authentication.lockActive(jwt)) return new Result.Unauthorized();
        if (!lockPlayer(playerId)) return new Result.NotFound();
        String code = codes.rotate(playerId, admin(jwt));
        authentication.requireUnexpired(jwt);
        return new Result.Code(code);
    }

    /** The player's current code, for the admin's QR. */
    @Transactional(readOnly = true)
    Result reveal(UUID playerId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND role = 'PLAYER' AND status IN ('ACTIVE', 'FROZEN'))
                """, Boolean.class, playerId))) {
            return new Result.NotFound();
        }
        return codes.reveal(playerId).<Result>map(Result.Code::new).orElseGet(Result.NotFound::new);
    }

    private boolean lockPlayer(UUID playerId) {
        return !jdbc.queryForList("""
                SELECT id FROM users WHERE id = ? AND role = 'PLAYER' AND status IN ('ACTIVE', 'FROZEN') FOR UPDATE
                """, UUID.class, playerId).isEmpty();
    }

    private static UUID admin(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

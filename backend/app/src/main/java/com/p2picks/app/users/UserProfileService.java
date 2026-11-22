package com.p2picks.app.users;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserProfileService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED }
    record Changes(String username, Integer hue) {}
    record Profile(UUID id, String username, int hue) {}
    record Result(Outcome outcome, Profile profile) {}
    static class UsernameTaken extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    UserProfileService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public Result update(Jwt jwt, Changes changes, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        List<Profile> users = jdbc.query("""
                SELECT id, username::text, hue FROM users WHERE id = ? AND status = 'ACTIVE'
                """, (rs, row) -> new Profile(rs.getObject("id", UUID.class),
                        rs.getString("username"), rs.getInt("hue")), userId);
        if (users.size() != 1) throw new IllegalStateException("Locked active user disappeared");
        Profile current = users.getFirst();
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "user.profile.update.v1",
                (changes.username() == null ? "-" : changes.username()) + "|" + changes.hue(),
                IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        if (claim == IdempotencyGuard.Claim.REPLAY) return new Result(Outcome.REPLAYED, current);
        String username = changes.username() == null ? current.username() : changes.username();
        int hue = changes.hue() == null ? current.hue() : changes.hue();
        if (!username.equals(current.username()) || hue != current.hue()) {
            if (!writeProfile(userId, username, hue)) throw new UsernameTaken();
            outbox.append(OutboxEvent.USER_PROFILE_CHANGED, userId, Map.of("userId", userId));
        }
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.UPDATED, new Profile(userId, username, hue));
    }

    private boolean writeProfile(UUID userId, String username, int hue) {
        Boolean updated = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("UPDATE users SET username = ?, hue = ? WHERE id = ?")) {
                statement.setString(1, username);
                statement.setInt(2, hue);
                statement.setObject(3, userId);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Locked user disappeared");
                connection.releaseSavepoint(savepoint);
                return true;
            } catch (SQLException exception) {
                if (!"23505".equals(exception.getSQLState())) throw exception;
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                return false;
            }
        });
        if (updated == null) throw new IllegalStateException("Profile update returned no result");
        return updated;
    }
}

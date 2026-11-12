package com.p2picks.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class MutationAuthentication {
    private final JdbcTemplate jdbc;

    MutationAuthentication(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static class ExpiredSession extends RuntimeException {}
    enum PairStatus { AVAILABLE, TARGET_UNAVAILABLE, UNAUTHORIZED }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActive(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        if (jdbc.query("""
                SELECT id FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), userId).isEmpty()) {
            return false;
        }
        return !jdbc.query("""
                SELECT id FROM auth_sessions
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR SHARE
                """, (rs, row) -> rs.getObject("id", UUID.class), sessionId, userId).isEmpty();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PairStatus lockActivePair(Jwt jwt, UUID targetId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        List<UserStatus> users = jdbc.query("""
                SELECT id, status FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """, (rs, row) -> new UserStatus(rs.getObject("id", UUID.class), rs.getString("status")),
                userId, targetId);
        boolean active = users.stream().anyMatch(user -> user.id().equals(userId) && user.status().equals("ACTIVE"));
        if (!active || jdbc.query("""
                SELECT id FROM auth_sessions
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR SHARE
                """, (rs, row) -> rs.getObject("id", UUID.class), sessionId, userId).isEmpty()) {
            return PairStatus.UNAUTHORIZED;
        }
        return users.stream().anyMatch(user -> user.id().equals(targetId) && !user.status().equals("DELETED"))
                ? PairStatus.AVAILABLE : PairStatus.TARGET_UNAVAILABLE;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, String> lockActiveWithTargets(Jwt jwt, List<UUID> targetIds) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        List<UUID> ids = new ArrayList<>(targetIds);
        ids.add(userId);
        String postgresArray = "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
        List<UserStatus> users = jdbc.query("""
                SELECT id, status FROM users WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE
                """, (rs, row) -> new UserStatus(rs.getObject("id", UUID.class), rs.getString("status")),
                postgresArray);
        boolean active = users.stream().anyMatch(user -> user.id().equals(userId) && user.status().equals("ACTIVE"));
        if (!active || jdbc.query("""
                SELECT id FROM auth_sessions
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR SHARE
                """, (rs, row) -> rs.getObject("id", UUID.class), sessionId, userId).isEmpty()) {
            return null;
        }
        Map<UUID, String> statuses = new LinkedHashMap<>();
        users.forEach(user -> statuses.put(user.id(), user.status()));
        return statuses;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUnexpired(Jwt jwt) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM auth_sessions
                    WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp())
                """, Boolean.class, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (!Boolean.TRUE.equals(active) || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())) {
            throw new ExpiredSession();
        }
    }

    private record UserStatus(UUID id, String status) {}
}

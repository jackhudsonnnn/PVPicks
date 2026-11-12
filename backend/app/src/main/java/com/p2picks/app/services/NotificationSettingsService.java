package com.p2picks.app;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationSettingsService {
    private static final String OPERATION = "notification-preferences.replace.v1";
    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;

    NotificationSettingsService(
            JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED }

    record Preferences(
            boolean enabled, boolean errors, boolean ticketStatus, String ticketScope,
            boolean chats, boolean invites, List<UUID> mutedTableIds) {
        Preferences {
            mutedTableIds = mutedTableIds.stream().sorted().toList();
        }

        String canonical() {
            return (enabled ? "1" : "0") + (errors ? "1" : "0") + (ticketStatus ? "1" : "0")
                    + (chats ? "1" : "0") + (invites ? "1" : "0") + "|" + ticketScope + "|"
                    + mutedTableIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        }
    }

    static class InvalidMute extends RuntimeException {}
    @Transactional
    public Outcome replace(Jwt jwt, String key, Preferences preferences) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) {
            return Outcome.UNAUTHORIZED;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, OPERATION, preferences.canonical());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return Outcome.REPLAYED;
        }

        Set<UUID> existing = new HashSet<>(jdbc.query("""
                SELECT table_id FROM notification_muted_tables WHERE user_id = ?
                """, (rs, row) -> rs.getObject("table_id", UUID.class), userId));
        for (UUID tableId : preferences.mutedTableIds()) {
            if (!existing.contains(tableId)) {
                List<UUID> memberships = jdbc.query("""
                        SELECT t.id FROM tables t JOIN table_members m ON m.table_id = t.id
                        WHERE t.id = ? AND t.archived_at IS NULL AND m.user_id = ?
                        FOR SHARE OF t, m
                        """, (rs, row) -> rs.getObject("id", UUID.class), tableId, userId);
                if (memberships.isEmpty()) {
                    throw new InvalidMute();
                }
            }
        }
        authentication.requireUnexpired(jwt);
        jdbc.update("""
                INSERT INTO notification_preferences
                    (user_id, enabled, errors, ticket_status, ticket_scope, chats, invites)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET enabled = EXCLUDED.enabled, errors = EXCLUDED.errors,
                    ticket_status = EXCLUDED.ticket_status, ticket_scope = EXCLUDED.ticket_scope,
                    chats = EXCLUDED.chats, invites = EXCLUDED.invites, updated_at = now()
                """, userId, preferences.enabled(), preferences.errors(), preferences.ticketStatus(),
                preferences.ticketScope(), preferences.chats(), preferences.invites());
        jdbc.update("DELETE FROM notification_muted_tables WHERE user_id = ?", userId);
        for (UUID tableId : preferences.mutedTableIds()) {
            jdbc.update("INSERT INTO notification_muted_tables (user_id, table_id) VALUES (?, ?)", userId, tableId);
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}

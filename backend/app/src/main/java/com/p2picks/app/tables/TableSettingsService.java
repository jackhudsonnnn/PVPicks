package com.p2picks.app.tables;

import com.p2picks.app.chat.SystemChatWriter;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableSettingsService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Changes(Boolean hostsOnlyBets, Boolean u2pickEnabled) {}
    private record Settings(boolean hostsOnlyBets, boolean u2pickEnabled) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SystemChatWriter systemChat;
    private final Outbox outbox;

    TableSettingsService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            SystemChatWriter systemChat, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.systemChat = systemChat;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome update(Jwt jwt, UUID tableId, Changes changes, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<Settings> tables = jdbc.query("""
                SELECT hosts_only_bets, u2pick_enabled FROM tables
                WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Settings(rs.getBoolean("hosts_only_bets"), rs.getBoolean("u2pick_enabled")),
                tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, userId);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return Outcome.NOT_FOUND;
        if (!roles.getFirst().equals("HOST")) return Outcome.FORBIDDEN;

        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "table.settings.update.v1",
                tableId + "|" + changes.hostsOnlyBets() + "|" + changes.u2pickEnabled(),
                IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;

        Settings current = tables.getFirst();
        boolean hostsChanged = changes.hostsOnlyBets() != null
                && changes.hostsOnlyBets() != current.hostsOnlyBets();
        boolean u2pickChanged = changes.u2pickEnabled() != null
                && changes.u2pickEnabled() != current.u2pickEnabled();
        if (hostsChanged || u2pickChanged) {
            jdbc.update("""
                    UPDATE tables SET hosts_only_bets = COALESCE(?, hosts_only_bets),
                        u2pick_enabled = COALESCE(?, u2pick_enabled) WHERE id = ?
                    """, changes.hostsOnlyBets(), changes.u2pickEnabled(), tableId);
            if (hostsChanged) {
                systemChat.write(tableId, changes.hostsOnlyBets()
                        ? "Only hosts can propose new bets" : "Host-only bet proposals turned off");
            }
            if (u2pickChanged) {
                systemChat.write(tableId, changes.u2pickEnabled()
                        ? "U2Pick custom bets turned on" : "U2Pick custom bets turned off");
            }
            outbox.append(OutboxEvent.TABLE_SETTINGS_CHANGED, tableId, Map.of("tableId", tableId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

}

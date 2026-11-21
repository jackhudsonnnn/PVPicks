package com.p2picks.app.tables;

import com.p2picks.app.chat.ChatMessageWriter;
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
    /** {@code blackjackAccess}: who may deal Blackjack, {@code hosts}, {@code everyone} or {@code off}. */
    record Changes(Boolean hostsOnlyBets, Boolean u2pickEnabled, Boolean pollsEnabled, String blackjackAccess) {
        Changes(Boolean hostsOnlyBets, Boolean u2pickEnabled, Boolean pollsEnabled) {
            this(hostsOnlyBets, u2pickEnabled, pollsEnabled, null);
        }
    }
    private record Settings(boolean hostsOnlyBets, boolean u2pickEnabled, boolean pollsEnabled,
            String blackjackAccess) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final ChatMessageWriter chatMessages;
    private final Outbox outbox;

    TableSettingsService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            ChatMessageWriter chatMessages, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.chatMessages = chatMessages;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome update(Jwt jwt, UUID tableId, Changes changes, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<Settings> tables = jdbc.query("""
                SELECT hosts_only_bets, u2pick_enabled, polls_enabled, blackjack_access FROM tables
                WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Settings(rs.getBoolean("hosts_only_bets"), rs.getBoolean("u2pick_enabled"),
                rs.getBoolean("polls_enabled"), rs.getString("blackjack_access")), tableId);
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
                tableId + "|" + changes.hostsOnlyBets() + "|" + changes.u2pickEnabled() + "|" + changes.pollsEnabled()
                        + (changes.blackjackAccess() == null ? "" : "|" + changes.blackjackAccess()),
                IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;

        Settings current = tables.getFirst();
        boolean hostsChanged = changes.hostsOnlyBets() != null
                && changes.hostsOnlyBets() != current.hostsOnlyBets();
        boolean u2pickChanged = changes.u2pickEnabled() != null
                && changes.u2pickEnabled() != current.u2pickEnabled();
        boolean pollsChanged = changes.pollsEnabled() != null
                && changes.pollsEnabled() != current.pollsEnabled();
        boolean blackjackChanged = changes.blackjackAccess() != null
                && !changes.blackjackAccess().equals(current.blackjackAccess());
        if (hostsChanged || u2pickChanged || pollsChanged || blackjackChanged) {
            jdbc.update("""
                    UPDATE tables SET hosts_only_bets = COALESCE(?, hosts_only_bets),
                        u2pick_enabled = COALESCE(?, u2pick_enabled), polls_enabled = COALESCE(?, polls_enabled),
                        blackjack_access = COALESCE(?, blackjack_access)
                    WHERE id = ?
                    """, changes.hostsOnlyBets(), changes.u2pickEnabled(), changes.pollsEnabled(),
                    changes.blackjackAccess(), tableId);
            if (blackjackChanged) {
                chatMessages.system(tableId, switch (changes.blackjackAccess()) {
                    case "hosts" -> "Only hosts can deal Blackjack";
                    case "off" -> "Blackjack turned off";
                    default -> "Anyone can deal Blackjack";
                });
            }
            if (hostsChanged) {
                chatMessages.system(tableId, changes.hostsOnlyBets()
                        ? "Only hosts can propose new bets" : "Host-only bet proposals turned off");
            }
            if (u2pickChanged) {
                chatMessages.system(tableId, changes.u2pickEnabled()
                        ? "U2Pick custom bets turned on" : "U2Pick custom bets turned off");
            }
            if (pollsChanged) {
                chatMessages.system(tableId, changes.pollsEnabled() ? "Polls turned on" : "Polls turned off");
            }
            outbox.append(OutboxEvent.TABLE_SETTINGS_CHANGED, tableId, Map.of("tableId", tableId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

}

package com.p2picks.app.tables;

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
class TableRemainderService {
    enum Rule {
        HOST("host", "Host"),
        PROPOSER("proposer", "Proposer"),
        EARLIEST("earliest", "Earliest pick"),
        RANDOM("random", "Random");

        private final String value;
        private final String label;

        Rule(String value, String label) {
            this.value = value;
            this.label = label;
        }

        static Rule parse(String value) {
            for (Rule rule : values()) {
                if (rule.value.equals(value)) return rule;
            }
            return null;
        }
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    static class ActiveBet extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    TableRemainderService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome set(Jwt jwt, UUID tableId, Rule rule, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<String> rules = jdbc.query("""
                SELECT remainder_rule FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getString("remainder_rule"), tableId);
        if (rules.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, userId);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return Outcome.NOT_FOUND;
        if (!roles.getFirst().equals("HOST")) return Outcome.FORBIDDEN;

        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "table.remainder.set.v1",
                tableId + "|" + rule.value);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!rules.getFirst().equals(rule.value)) {
            Boolean active = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM bets WHERE table_id = ? AND status = 'ACTIVE')
                    """, Boolean.class, tableId);
            authentication.requireUnexpired(jwt);
            if (active == null) throw new IllegalStateException("Active bet check returned no result");
            if (active) throw new ActiveBet();
            jdbc.update("UPDATE tables SET remainder_rule = ? WHERE id = ?", rule.value, tableId);
            UUID messageId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (id, table_id, kind, text)
                    VALUES (?, ?, 'system', ?)
                    """, messageId, tableId, "Leftover cents set to: " + rule.label);
            outbox.append(OutboxEvent.TABLE_REMAINDER_CHANGED, tableId, Map.of("tableId", tableId));
            outbox.append(OutboxEvent.CHAT_MESSAGE_CREATED, tableId,
                    Map.of("tableId", tableId, "messageId", messageId, "kind", "system"));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}

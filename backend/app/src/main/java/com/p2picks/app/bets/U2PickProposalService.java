package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.DebtQuery;
import com.p2picks.modules.bets.api.U2PickSettler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class U2PickProposalService {
    private static final String OPERATION = "bet.u2pick.propose.v1";

    enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Result(Outcome outcome, UUID betId) {}
    record Proposal(U2PickDefinition definition, ObjectNode config, long wagerCents, int durationSec,
                    boolean forFun, U2PickSettler.Rule settlerRule) {
        Proposal {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(config, "config");
            if (wagerCents < 0 || (!forFun && (wagerCents < 10 || wagerCents > 1000))) {
                throw new IllegalArgumentException("Invalid wager");
            }
            if (durationSec < 30 || durationSec > 600) throw new IllegalArgumentException("Invalid pick window");
            config = config.deepCopy();
            settlerRule = settlerRule == null ? U2PickSettler.Rule.HOSTS : settlerRule;
        }

        @Override
        public ObjectNode config() {
            return config.deepCopy();
        }
    }

    static class DebtCapExceeded extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final ObjectMapper json;

    U2PickProposalService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, DebtQuery debt, ObjectMapper json) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
        this.json = json;
    }

    @Transactional
    public Result create(Jwt jwt, UUID tableId, Proposal proposal, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        Objects.requireNonNull(tableId, "tableId");
        Objects.requireNonNull(proposal, "proposal");
        ObjectNode config = canonicalConfig(proposal.config(), proposal.definition());
        String configJson = write(config);
        String choicesJson = write(json.valueToTree(proposal.definition().choices()));
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.put("tableId", tableId.toString());
        fingerprint.put("question", proposal.definition().question());
        fingerprint.set("choices", json.valueToTree(proposal.definition().choices()));
        fingerprint.set("config", config);
        fingerprint.put("wagerCents", proposal.wagerCents());
        fingerprint.put("durationSec", proposal.durationSec());
        fingerprint.put("forFun", proposal.forFun());
        fingerprint.put("settledBy", proposal.settlerRule().name());

        UUID actor = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return failure(Outcome.UNAUTHORIZED);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return failure(Outcome.FORBIDDEN);
        }
        List<Settings> tables = jdbc.query("""
                SELECT hosts_only_bets, u2pick_enabled FROM tables
                WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Settings(rs.getBoolean("hosts_only_bets"),
                        rs.getBoolean("u2pick_enabled")), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return failure(Outcome.NOT_FOUND);
        }
        List<Member> members = jdbc.query("""
                SELECT role, can_bet FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new Member(rs.getString("role"), rs.getBoolean("can_bet")), tableId, actor);
        authentication.requireUnexpired(jwt);
        if (members.isEmpty()) return failure(Outcome.NOT_FOUND);
        Member member = members.getFirst();
        if (!member.canBet()) return failure(Outcome.FORBIDDEN);

        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, OPERATION, write(fingerprint),
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return failure(Outcome.CONFLICT);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            UUID betId = idempotency.responseReference(actor, key, OPERATION);
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.REPLAYED, betId);
        }
        Settings settings = tables.getFirst();
        if (!settings.u2pickEnabled() || (settings.hostsOnlyBets() && !member.role().equals("HOST"))) {
            idempotency.discardNewClaim(actor, key);
            authentication.requireUnexpired(jwt);
            return failure(Outcome.FORBIDDEN);
        }
        if (!debt.canRiskForLockedUser(actor, proposal.wagerCents(), proposal.forFun())) {
            throw new DebtCapExceeded();
        }
        UUID betId = UUID.randomUUID();
        jdbc.update("""
                WITH stamp AS MATERIALIZED (SELECT clock_timestamp() AS at)
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at, created_at, settler_rule)
                SELECT ?, ?, ?, 'u2pick', ?::jsonb, ?, ?::jsonb, ?, ?,
                       stamp.at + (? * interval '1 second'), stamp.at, ? FROM stamp
                """, betId, tableId, actor, configJson, proposal.definition().question(), choicesJson,
                proposal.wagerCents(), proposal.forFun(), proposal.durationSec(),
                proposal.settlerRule().name().toLowerCase(java.util.Locale.ROOT));
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'bet', ?, 'bet.created.v1', jsonb_build_object('tableId', ?::text, 'betId', ?::text))
                """, UUID.randomUUID(), betId.toString(), tableId.toString(), betId.toString());
        idempotency.saveResponseReference(actor, key, OPERATION, betId);
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.CREATED, betId);
    }

    private ObjectNode canonicalConfig(ObjectNode config, U2PickDefinition definition) {
        Map<String, JsonNode> fields = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : config.properties()) {
            if (!PostgresText.valid(field.getKey())) throw new IllegalArgumentException("Invalid config key");
            JsonNode value = field.getValue();
            if (value.isTextual()) {
                requireValidText(value);
            } else if (value.isArray()) {
                for (JsonNode element : value) requireValidText(element);
            } else if (!value.isNumber() || (value.isDouble() && !Double.isFinite(value.doubleValue()))
                    || (value.isFloat() && !Float.isFinite(value.floatValue()))) {
                throw new IllegalArgumentException("Invalid config value");
            }
            fields.put(field.getKey(), value);
        }
        fields.put("question", json.getNodeFactory().textNode(definition.question()));
        fields.put("options", json.valueToTree(definition.choices()));
        ObjectNode canonical = json.createObjectNode();
        fields.forEach(canonical::set);
        return canonical;
    }

    private static void requireValidText(JsonNode value) {
        if (!value.isTextual() || !PostgresText.valid(value.textValue())) {
            throw new IllegalArgumentException("Invalid config text");
        }
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize bet proposal", exception);
        }
    }

    private static Result failure(Outcome outcome) {
        return new Result(outcome, null);
    }

    private record Settings(boolean hostsOnlyBets, boolean u2pickEnabled) {}
    private record Member(String role, boolean canBet) {}
}

package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
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

    private final BetProposalWriter writer;
    private final ObjectMapper json;

    U2PickProposalService(BetProposalWriter writer, ObjectMapper json) {
        this.writer = writer;
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

        BetProposalWriter.Result result = writer.create(jwt, tableId, key, OPERATION, write(fingerprint), true,
                () -> new BetProposalWriter.NewBet("u2pick", null, configJson, proposal.definition().question(),
                        choicesJson, proposal.wagerCents(), proposal.forFun(), proposal.durationSec(),
                        proposal.settlerRule().name().toLowerCase(java.util.Locale.ROOT), null, null));
        return new Result(Outcome.valueOf(result.outcome().name()), result.betId());
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

}

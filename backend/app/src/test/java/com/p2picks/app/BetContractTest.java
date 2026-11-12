package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BetContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonSchema betSchema;

    @BeforeAll
    static void loadContract() throws IOException {
        betSchema = OpenApiSchemas.load("Bet");
    }

    @Test
    void activeBetAllowsOnlyTheCallersPick() {
        ObjectNode bet = bet("ACTIVE");
        bet.put("myPick", "Home");
        bet.put("myParticipation", "picked");
        bet.put("pickCount", 1);
        assertThat(betSchema.validate(bet)).isEmpty();

        bet.putArray("picks").addObject()
                .put("userId", "30aa2f85-cd90-41b7-859b-9a7a54280ae5")
                .put("choice", "Away");
        assertThat(betSchema.validate(bet)).isNotEmpty();
        bet.remove("picks");
        bet.putArray("participantIds").add("30aa2f85-cd90-41b7-859b-9a7a54280ae5");
        assertThat(betSchema.validate(bet)).isNotEmpty();
        bet.remove("participantIds");
        bet.putArray("noGuessUserIds").add("30aa2f85-cd90-41b7-859b-9a7a54280ae5");
        assertThat(betSchema.validate(bet)).isNotEmpty();
    }

    @Test
    void unresolvedBetsCannotExposeResultsOrDeltas() {
        for (String status : new String[] {"ACTIVE", "PENDING"}) {
            for (String field : new String[] {"calledBy", "winningChoice", "washReason", "deltasCents"}) {
                ObjectNode bet = bet(status);
                addResultField(bet, field);
                assertThat(betSchema.validate(bet)).as("%s with %s", status, field).isNotEmpty();
            }
        }
    }

    @Test
    void settledBetCanContainResultFields() {
        ObjectNode bet = bet("SETTLED");
        addResultField(bet, "calledBy");
        addResultField(bet, "winningChoice");
        addResultField(bet, "deltasCents");
        assertThat(betSchema.validate(bet)).isEmpty();
    }

    @Test
    void lockedBetsCanRevealPicks() {
        ObjectNode bet = bet("PENDING");
        bet.putArray("picks").addObject()
                .put("userId", "30aa2f85-cd90-41b7-859b-9a7a54280ae5")
                .put("choice", "Away");
        bet.put("pickCount", 1);
        bet.putArray("noGuessUserIds").add("f95c6d6a-394a-4b2d-b072-a29ed48cfd99");
        assertThat(betSchema.validate(bet)).isEmpty();
        bet.remove("noGuessUserIds");
        assertThat(betSchema.validate(bet)).isNotEmpty();
    }

    @Test
    void requiresSeparateTablePointsSettlementState() {
        ObjectNode bet = bet("SETTLED");
        bet.put("pointsSettled", true);
        assertThat(betSchema.validate(bet)).isEmpty();
        bet.remove("pointsSettled");
        assertThat(betSchema.validate(bet)).isNotEmpty();

        for (String status : new String[] {"ACTIVE", "PENDING"}) {
            ObjectNode unresolved = bet(status);
            unresolved.put("pointsSettled", true);
            assertThat(betSchema.validate(unresolved)).as(status).isNotEmpty();
        }
    }

    @Test
    void distinguishesNoGuessFromNeverJoiningWithoutExposingOtherParticipants() {
        ObjectNode bet = bet("ACTIVE");
        assertThat(betSchema.validate(bet)).isEmpty();
        bet.put("myParticipation", "no_guess");
        assertThat(betSchema.validate(bet)).isEmpty();
        bet.put("myParticipation", "picked");
        assertThat(betSchema.validate(bet)).isNotEmpty();
        bet.put("myPick", "Home");
        bet.put("pickCount", 1);
        assertThat(betSchema.validate(bet)).isEmpty();
        bet.put("myParticipation", "no_guess");
        assertThat(betSchema.validate(bet)).isNotEmpty();
    }

    @Test
    void lockedHostRemainderRequiresItsSnapshottedRecipient() {
        for (String status : new String[] {"PENDING", "SETTLED", "WASHED"}) {
            ObjectNode locked = bet(status);
            assertThat(betSchema.validate(locked)).as(status).isEmpty();
            locked.remove("remainderHostId");
            assertThat(betSchema.validate(locked)).as(status).isNotEmpty();
            locked.remove("remainderRule");
            assertThat(betSchema.validate(locked)).as(status).isNotEmpty();
            locked.put("remainderRule", "earliest");
            assertThat(betSchema.validate(locked)).as(status).isEmpty();
            locked.put("remainderHostId", "f95c6d6a-394a-4b2d-b072-a29ed48cfd99");
            assertThat(betSchema.validate(locked)).as(status).isNotEmpty();
        }

        ObjectNode active = bet("ACTIVE");
        active.put("remainderRule", "host");
        active.put("remainderHostId", "f95c6d6a-394a-4b2d-b072-a29ed48cfd99");
        assertThat(betSchema.validate(active)).isNotEmpty();
    }

    @Test
    void customBetAllowsSportsPassButRejectsNoGuessChoices() throws IOException {
        JsonSchema proposalSchema = OpenApiSchemas.load("BetProposal");
        ObjectNode proposal = JSON.createObjectNode();
        proposal.put("modeId", "u2pick");
        proposal.putObject("config");
        proposal.put("question", "Next play?");
        proposal.putArray("choices").add("Pass").add("Run");
        proposal.put("wagerCents", 100);
        proposal.put("durationSec", 60);
        proposal.put("forFun", false);
        assertThat(proposalSchema.validate(proposal)).isEmpty();
        proposal.putArray("choices").add(" NO GUESS ").add("Run");
        assertThat(proposalSchema.validate(proposal)).isNotEmpty();
        proposal.putArray("choices").add("__no_guess__").add("Run");
        assertThat(proposalSchema.validate(proposal)).isNotEmpty();
    }

    private static ObjectNode bet(String status) {
        ObjectNode bet = JSON.createObjectNode();
        bet.put("id", "95586d98-8dae-455a-b642-51bd5c8cb8f8");
        bet.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        bet.put("proposerId", "52a73783-b3cf-438a-8174-ac235c54eb6e");
        bet.put("modeId", "u2pick");
        bet.putObject("config");
        bet.put("question", "Who wins?");
        bet.putArray("choices").add("Home").add("Away");
        bet.put("wagerCents", 100);
        bet.put("forFun", false);
        bet.put("status", status);
        bet.put("pointsSettled", false);
        bet.put("pickCount", 0);
        bet.put("myParticipation", "not_joined");
        if (!status.equals("ACTIVE")) {
            bet.putArray("picks");
            bet.putArray("noGuessUserIds");
            bet.put("remainderRule", "host");
            bet.put("remainderHostId", "f95c6d6a-394a-4b2d-b072-a29ed48cfd99");
        }
        bet.put("locksAt", "2026-11-11T12:00:00Z");
        bet.putNull("myPick");
        return bet;
    }

    private static void addResultField(ObjectNode bet, String field) {
        switch (field) {
            case "calledBy" -> bet.put(field, "30aa2f85-cd90-41b7-859b-9a7a54280ae5");
            case "winningChoice" -> bet.put(field, "Home");
            case "washReason" -> bet.put(field, "No winner");
            case "deltasCents" -> bet.putObject(field).put("user", 100);
            default -> throw new IllegalArgumentException("Unexpected field: " + field);
        }
    }
}

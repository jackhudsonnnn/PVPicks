package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/** Runs the client's U2Pick workflow through real HTTP; opt in with PVPICKS_FRONTEND_INTEGRATION=true. */
class BettingFrontendIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private BetLockService locks;

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void clientRunsTheU2PickLifecycle() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betuihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betuiplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bet UI', 'T', 'BP8X3A')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        String file = "src/features/auth/betting-integration.test.tsx";

        runFrontendTest(file, Map.of("PVPICKS_TEST_TABLE_ID", table.toString(), "PVPICKS_TEST_PHASE", "pick"));
        UUID bet = jdbc.queryForObject("SELECT id FROM bets WHERE table_id = ?", UUID.class, table);
        assertThat(bet).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(2);
        JsonNode hostView = json.readTree(mvc.perform(get("/bets/" + bet).cookie(loginAs("betuihost")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode playerView = json.readTree(mvc.perform(get("/bets/" + bet).cookie(loginAs("betuiplayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostView.path("myPick").asText()).isEqualTo("Cats");
        assertThat(playerView.path("myPick").asText()).isEqualTo("Dogs");
        assertThat(hostView.has("picks")).isFalse();
        assertThat(playerView.has("picks")).isFalse();

        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", bet);
        assertThat(locks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        runFrontendTest(file, Map.of("PVPICKS_TEST_TABLE_ID", table.toString(), "PVPICKS_TEST_PHASE", "result"));
        assertThat(jdbc.queryForMap("""
                SELECT status, winning_choice, points_settled_at IS NOT NULL AS settled FROM bets WHERE id = ?
                """, bet)).containsEntry("status", "SETTLED").containsEntry("winning_choice", "Cats")
                .containsEntry("settled", true);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_lines WHERE table_id = ? AND payer_id = ? AND payee_id = ?
                """, Integer.class, table, player, host)).isEqualTo(1);
    }
}

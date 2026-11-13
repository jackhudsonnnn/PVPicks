package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.settlement.TablePointsSettlementService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;

class FrontendPreviewIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void bettingPreviewRunsAgainstIsolatedBackend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betuihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betuiplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bet Preview', 'T', 'BP8X3A')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        ProcessBuilder builder = new ProcessBuilder("npm", "test", "--",
                "src/features/auth/betting-integration.test.tsx")
                .directory(Path.of("../../client").toFile())
                .inheritIO();
        builder.environment().put("PVPICKS_TEST_PORT", Integer.toString(serverPort));
        builder.environment().put("PVPICKS_TEST_TABLE_ID", table.toString());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("frontend integration timed out").isTrue();
            assertThat(process.exitValue()).as("frontend integration exit code").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
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
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void settlementPreviewRunsAgainstIsolatedBackend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'previewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'previewpayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Preview', 'T', 'P4E8WA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -125);
        Jwt hostJwt = decoder.decode(loginAs("previewhost").getValue());
        pointsSettlement.settle(hostJwt, table, "preview-points");
        assertThat(reviewRevision(loginAs("previewpayer"), host)).isNotNull();

        ProcessBuilder builder = new ProcessBuilder("npm", "test", "--",
                "src/features/auth/settlement-integration.test.tsx")
                .directory(Path.of("../../client").toFile())
                .inheritIO();
        builder.environment().put("PVPICKS_TEST_PORT", Integer.toString(serverPort));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("frontend integration timed out").isTrue();
            assertThat(process.exitValue()).as("frontend integration exit code").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertThat(reviewRevision(loginAs("previewpayer"), host)).isNull();
        assertThat(reviewRevision(loginAs("previewhost"), payer)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("sent");
    }
}

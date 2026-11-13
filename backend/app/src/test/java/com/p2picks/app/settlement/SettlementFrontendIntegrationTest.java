package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.IntegrationTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;

/** Runs the client's receipt and review workflow through real HTTP; opt in with PVPICKS_FRONTEND_INTEGRATION=true. */
class SettlementFrontendIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void clientReviewsAChangedBalanceAndSendsTheGroup() throws Exception {
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

        runFrontendTest("src/features/auth/settlement-integration.test.tsx", Map.of());
        assertThat(reviewRevision(loginAs("previewpayer"), host)).isNull();
        assertThat(reviewRevision(loginAs("previewhost"), payer)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("sent");
    }
}

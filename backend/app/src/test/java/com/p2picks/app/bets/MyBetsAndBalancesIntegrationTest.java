package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class MyBetsAndBalancesIntegrationTest extends IntegrationTestSupport {
    private final UUID host = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID tableA = UUID.randomUUID();
    private final UUID tableB = UUID.randomUUID();
    private final UUID foreign = UUID.randomUUID();
    private final UUID archived = UUID.randomUUID();

    private void seed(String prefix) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'),
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'ADMIN', 'ACTIVE')
                """, host, prefix + "host", hash, member, prefix + "member", hash,
                outsider, prefix + "out", hash, admin, prefix + "admin", hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at) VALUES
                (?, 'A', 'T', ?, NULL), (?, 'B', 'T', ?, NULL), (?, 'F', 'T', ?, NULL), (?, 'X', 'T', ?, now())
                """, tableA, code(), tableB, code(), foreign, code(), archived, code());
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'HOST')
                """, tableA, host, tableA, member, tableB, member, foreign, outsider, archived, member);
    }

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static String code() {
        StringBuilder code = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    private UUID bet(UUID table, String question, String status, int minutesAgo, boolean forFun) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, created_at)
                VALUES (?, ?, (SELECT user_id FROM table_members WHERE table_id = ? LIMIT 1), 'u2pick', '{}'::jsonb,
                        ?, '["Yes","No"]'::jsonb, ?, ?, now() + interval '1 hour', now() - make_interval(mins => ?))
                """, id, table, table, question, forFun ? 0 : 100, forFun, minutesAgo);
        if (!status.equals("ACTIVE")) {
            jdbc.update("""
                    UPDATE bets SET status = ?, locked_at = now(), remainder_rule = 'proposer',
                                    closed_at = CASE WHEN ? IN ('SETTLED', 'WASHED') THEN now() END
                    WHERE id = ?
                    """, status, status, id);
        }
        return id;
    }

    private JsonNode read(String path, Cookie session) throws Exception {
        return json.readTree(mvc.perform(get(path).cookie(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void myBetsListsCurrentTablesNewestFirstWithPagingCountsAndPrivacy() throws Exception {
        seed("mybets");
        UUID oldA = bet(tableA, "Old A?", "SETTLED", 30, false);
        UUID activeA = bet(tableA, "Active A?", "ACTIVE", 20, false);
        UUID pendingB = bet(tableB, "Pending B?", "PENDING", 10, false);
        bet(foreign, "Foreign?", "ACTIVE", 5, false);
        bet(archived, "Archived?", "ACTIVE", 1, false);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)",
                activeA, host, activeA, member);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No')",
                activeA, host, activeA, member);
        Cookie memberSession = loginAs("mybetsmember");

        mvc.perform(get("/me/bets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/me/bets").cookie(loginAs("mybetsadmin"))).andExpect(status().isForbidden());
        mvc.perform(get("/me/bets?status=OPEN").cookie(memberSession)).andExpect(status().isBadRequest());
        mvc.perform(get("/me/bets?limit=0").cookie(memberSession)).andExpect(status().isBadRequest());
        mvc.perform(get("/me/bets?limit=101").cookie(memberSession)).andExpect(status().isBadRequest());
        mvc.perform(get("/me/bets?cursor=not-a-cursor").cookie(memberSession)).andExpect(status().isBadRequest());
        mvc.perform(get("/me/bets?tableId=" + foreign).cookie(memberSession)).andExpect(status().isNotFound());

        JsonNode all = read("/me/bets", memberSession);
        assertThat(OpenApiSchemas.load("BetPage").validate(all)).isEmpty();
        List<String> ids = new ArrayList<>();
        all.path("items").forEach(bet -> ids.add(bet.path("id").asText()));
        assertThat(ids).containsExactly(pendingB.toString(), activeA.toString(), oldA.toString());
        assertThat(all.path("nextCursor").isNull()).isTrue();
        assertThat(all.path("counts").path("ACTIVE").asLong()).isEqualTo(1);
        assertThat(all.path("counts").path("PENDING").asLong()).isEqualTo(1);
        assertThat(all.path("counts").path("SETTLED").asLong()).isEqualTo(1);
        assertThat(all.path("counts").path("WASHED").asLong()).isZero();
        JsonNode active = all.path("items").get(1);
        assertThat(active.path("myPick").asText()).isEqualTo("No");
        assertThat(active.has("picks")).isFalse();

        JsonNode first = read("/me/bets?limit=2", memberSession);
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode second = read("/me/bets?limit=2&cursor=" + cursor, memberSession);
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(oldA.toString());
        assertThat(second.path("nextCursor").isNull()).isTrue();

        JsonNode pending = read("/me/bets?status=PENDING", memberSession);
        assertThat(pending.path("items").size()).isEqualTo(1);
        assertThat(pending.path("items").get(0).path("id").asText()).isEqualTo(pendingB.toString());
        assertThat(pending.path("counts").path("ACTIVE").asLong()).isEqualTo(1);

        JsonNode onlyA = read("/me/bets?tableId=" + tableA, memberSession);
        assertThat(onlyA.path("items").size()).isEqualTo(2);
        assertThat(onlyA.path("counts").path("PENDING").asLong()).isZero();
    }

    @Test
    void balancesSumUnsettledResultsAndSubtractLockedWagers() throws Exception {
        seed("balance");
        UUID resolved = bet(tableA, "Resolved?", "SETTLED", 40, false);
        UUID alreadySettled = bet(tableA, "Settled?", "SETTLED", 30, false);
        UUID locked = bet(tableA, "Locked?", "PENDING", 20, false);
        UUID lockedForFun = bet(tableA, "Fun?", "PENDING", 15, true);
        UUID active = bet(tableA, "Active?", "ACTIVE", 10, false);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents) VALUES
                    (?, ?, ?, ?, 150), (?, ?, ?, ?, -150), (?, ?, ?, ?, 900), (?, ?, ?, ?, -900)
                    """, UUID.randomUUID(), tableA, resolved, host, UUID.randomUUID(), tableA, resolved, member,
                    UUID.randomUUID(), tableA, alreadySettled, host, UUID.randomUUID(), tableA, alreadySettled, member);
            jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", alreadySettled);
        });
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?)
                """, locked, member, lockedForFun, member, active, host);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'Yes'), (?, ?, 'Yes')
                """, locked, member, lockedForFun, member, active, host);
        Cookie hostSession = loginAs("balancehost");

        mvc.perform(get("/tables/" + tableA + "/balances")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tables/" + tableA + "/balances").cookie(loginAs("balanceout")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + tableA + "/balances").cookie(loginAs("balanceadmin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tables/" + archived + "/balances").cookie(loginAs("balancemember")))
                .andExpect(status().isNotFound());

        JsonNode balances = read("/tables/" + tableA + "/balances", hostSession);
        assertThat(balances.size()).isEqualTo(2);
        for (JsonNode balance : balances) {
            assertThat(OpenApiSchemas.load("TableBalance").validate(balance)).isEmpty();
            if (balance.path("userId").asText().equals(host.toString())) {
                // The Active pick is never counted.
                assertThat(balance.path("settleCents").asLong()).isEqualTo(150);
                assertThat(balance.path("trueCents").asLong()).isEqualTo(150);
            } else {
                // Only the non-4fun locked wager comes off.
                assertThat(balance.path("settleCents").asLong()).isEqualTo(-150);
                assertThat(balance.path("trueCents").asLong()).isEqualTo(-250);
            }
        }
    }
}

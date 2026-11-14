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
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** The table page's reads: the live bet set, what the viewer may do, a member's bets and record, and the modes. */
class BetReadModelIntegrationTest extends IntegrationTestSupport {
    private final UUID host = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();

    private void seed(String prefix) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, host, prefix + "h", hash, alice, prefix + "a", hash, bob, prefix + "b", hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Reads', 'T', ?)", table, code());
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, alice, table, bob);
    }

    private static String code() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return code.toString();
    }

    /** A U2Pick by the host that Alice picked "Yes" in and Bob picked "No" in, minutes ago, in the given state. */
    private UUID bet(String status, int minutesAgo, boolean forFun, boolean pointsSettled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, created_at, settler_rule)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Q?', '["Yes","No"]'::jsonb, ?, ?,
                        now() + interval '1 hour', now() - make_interval(mins => ?), 'hosts')
                """, id, table, host, forFun ? 0 : 100, forFun, minutesAgo);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", id, alice, id, bob);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No')",
                id, alice, id, bob);
        if (!status.equals("ACTIVE")) {
            jdbc.update("""
                    UPDATE bets SET status = ?, locked_at = now(), remainder_rule = 'proposer',
                                    closed_at = CASE WHEN ? IN ('SETTLED', 'WASHED') THEN now() END,
                                    winning_choice = CASE WHEN ? = 'SETTLED' THEN 'Yes' END,
                                    points_settled_at = CASE WHEN ? THEN now() END
                    WHERE id = ?
                    """, status, status, status, pointsSettled, id);
        }
        return id;
    }

    private JsonNode read(String path, Cookie session) throws Exception {
        return json.readTree(mvc.perform(get(path).cookie(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode bets) {
        List<String> ids = new ArrayList<>();
        bets.forEach(bet -> ids.add(bet.path("id").asText()));
        return ids;
    }

    @Test
    void tablePageReadsTheLiveSetWithWhatTheViewerMayDo() throws Exception {
        seed("rm");
        UUID active = bet("ACTIVE", 50, false, false);
        UUID pending = bet("PENDING", 40, false, false);
        UUID awaiting = bet("SETTLED", 30, false, false);
        bet("SETTLED", 20, false, true);
        bet("SETTLED", 10, true, false);
        bet("WASHED", 5, false, false);
        Cookie hostSession = loginAs("rmh");
        Cookie aliceSession = loginAs("rma");

        JsonNode live = read("/tables/" + table + "/bets", hostSession);
        assertThat(ids(live)).as("open, plus resolved bets awaiting points settlement, oldest first")
                .containsExactly(active.toString(), pending.toString(), awaiting.toString());
        var betSchema = OpenApiSchemas.load("Bet");
        live.forEach(bet -> assertThat(betSchema.validate(bet)).isEmpty());
        assertThat(live.get(1).path("viewerCanCall").asBoolean()).as("a host calls a hosts-settled U2Pick").isTrue();
        assertThat(live.get(0).path("viewerCanCall").asBoolean()).as("not while Active").isFalse();
        assertThat(live.get(1).path("viewerCanOverride").asBoolean()).as("U2Pick has no override").isFalse();
        assertThat(read("/tables/" + table + "/bets", aliceSession).get(1).path("viewerCanCall").asBoolean())
                .as("a member can't call a hosts-settled bet").isFalse();

        jdbc.update("UPDATE bets SET settler_rule = 'non_participants' WHERE id = ?", pending);
        assertThat(read("/bets/" + pending, aliceSession).path("viewerCanCall").asBoolean())
                .as("Alice picked, so she can't call a non-participants bet").isFalse();
        assertThat(read("/bets/" + pending, hostSession).path("viewerCanCall").asBoolean()).isTrue();
    }

    @Test
    void membersBetsArePagedNewestFirstAndActivePicksStayPrivate() throws Exception {
        seed("mb");
        UUID active = bet("ACTIVE", 40, false, false);
        UUID pending = bet("PENDING", 30, false, false);
        UUID settled = bet("SETTLED", 20, false, true);
        UUID washed = bet("WASHED", 10, false, false);
        Cookie hostSession = loginAs("mbh");
        Cookie aliceSession = loginAs("mba");
        String path = "/tables/" + table + "/members/" + alice + "/bets";

        JsonNode first = read(path + "?limit=2", hostSession);
        assertThat(OpenApiSchemas.load("BetPage").validate(first)).isEmpty();
        assertThat(ids(first.path("items"))).containsExactly(washed.toString(), settled.toString());
        JsonNode second = read(path + "?limit=2&cursor=" + first.path("nextCursor").asText(), hostSession);
        assertThat(ids(second.path("items"))).as("Alice's Active pick is private from the host")
                .containsExactly(pending.toString());
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(ids(read(path, aliceSession).path("items"))).as("but she sees her own")
                .contains(active.toString());
        mvc.perform(get(path).cookie(loginAs("mbb")).param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/tables/" + UUID.randomUUID() + "/members/" + alice + "/bets").cookie(hostSession))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordsCountResolvedNon4funBetsWithAPick() throws Exception {
        seed("rc");
        UUID won = bet("SETTLED", 40, false, true);
        appendDebtDelta(won, table, alice, bob, 100);
        UUID lost = bet("SETTLED", 30, false, false);
        appendDebtDelta(lost, table, alice, bob, -50);
        bet("SETTLED", 20, true, false);
        bet("WASHED", 10, false, false);
        bet("PENDING", 5, false, false);
        JsonNode record = read("/tables/" + table + "/members/" + alice + "/record", loginAs("rcb"));
        assertThat(OpenApiSchemas.load("MemberRecord").validate(record)).isEmpty();
        assertThat(record.toString()).isEqualTo("{\"played\":2,\"won\":1,\"lost\":1,\"netCents\":50}");
        mvc.perform(get("/tables/" + table + "/members/" + alice + "/record")).andExpect(status().isUnauthorized());
    }

    @Test
    void modesSayWhichCanBeProposed() throws Exception {
        seed("md");
        JsonNode modes = read("/modes", loginAs("mdh"));
        List<String> available = new ArrayList<>();
        var modeSchema = OpenApiSchemas.load("ModeAvailability");
        modes.forEach(mode -> {
            assertThat(modeSchema.validate(mode)).isEmpty();
            if (mode.path("available").asBoolean()) available.add(mode.path("id").asText());
        });
        assertThat(modes.size()).isEqualTo(8);
        assertThat(available).containsExactly("u2pick", "total_disaster", "give_and_take");
    }
}

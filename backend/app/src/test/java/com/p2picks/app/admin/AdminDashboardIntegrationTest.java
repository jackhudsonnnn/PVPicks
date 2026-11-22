package com.p2picks.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AdminDashboardIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Autowired private PerfRecorder recorder;

    private UUID user(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 1, ?, ?, 'ACTIVE')
                """, id, username, passwords.encode("a secure test password"), role);
        return id;
    }

    private UUID table(String name, UUID... members) {
        UUID id = UUID.randomUUID();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(31)));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, '🎲', ?)", id, name,
                code.toString());
        for (int i = 0; i < members.length; i++) {
            jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, ?)", id, members[i],
                    i == 0 ? "HOST" : "MEMBER");
        }
        return id;
    }

    private JsonNode read(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        return json.readTree(mvc.perform(request.cookie(session)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private ResultActions write(MockHttpServletRequestBuilder request, Cookie session, String body) throws Exception {
        Csrf csrf = csrf();
        return mvc.perform(request.cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int logged(UUID subject, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE subject_id = ? AND action = ?",
                Integer.class, subject, action);
    }

    @Test
    void auditCountsARecordLeavesOutActiveBetsAndLogsWhoLooked() throws Exception {
        UUID player = user("aud_p", "PLAYER");
        UUID other = user("aud_o", "PLAYER");
        user("aud_admin", "ADMIN");
        UUID table = table("Audit", player, other);
        // Won one, lost one (u2pick, settled), one washed, one 4fun win, one still Active.
        UUID won = insertDebtBet(table, player, other, "SETTLED", false, true, false, 100, 100);
        UUID lost = insertDebtBet(table, player, other, "SETTLED", false, true, false, 100, -100);
        jdbc.update("UPDATE bets SET winning_choice = 'Yes' WHERE id = ?", won);
        jdbc.update("UPDATE bets SET winning_choice = 'No' WHERE id = ?", lost);
        insertDebtBet(table, player, other, "WASHED", false, true, false, 100, 0);
        UUID fun = insertDebtBet(table, player, other, "SETTLED", true, true, false, 0, 0);
        jdbc.update("UPDATE bets SET winning_choice = 'Yes' WHERE id = ?", fun);
        insertDebtBet(table, player, other, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                UUID.randomUUID(), table, player, "Meet me at the 50% line");
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                UUID.randomUUID(), table, player, "nothing to see");
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text, poll_options)
                VALUES (?, ?, 'poll', ?, 'Pizza?', ARRAY['Pepperoni', 'Plain'])
                """, UUID.randomUUID(), table, player);
        jdbc.update("""
                INSERT INTO reports (id, reporter_id, target_id, reason, note, table_id) VALUES (?, ?, ?, 'spam', '', ?)
                """, UUID.randomUUID(), other, player, table);
        insertDebtReceipt(table, other, player, other, 250, "paid");

        Cookie admin = loginAs("aud_admin");
        JsonNode audit = read(get("/admin/users/" + player + "/audit"), admin);
        assertThat(OpenApiSchemas.load("UserAudit").validate(audit)).isEmpty();
        assertThat(audit.at("/profile/username").asText()).isEqualTo("aud_p");
        assertThat(audit.at("/betting/decided/won").asInt()).isEqualTo(1);
        assertThat(audit.at("/betting/decided/lost").asInt()).isEqualTo(1);
        assertThat(audit.at("/betting/winRate").asDouble()).isEqualTo(0.5);
        assertThat(audit.at("/betting/washed").asInt()).isEqualTo(1);
        assertThat(audit.at("/betting/fourFun/won").asInt()).isEqualTo(1);
        // The Active bet's pick is left out of every count until it locks.
        assertThat(audit.at("/betting/picks").asInt()).isEqualTo(4);
        assertThat(audit.at("/betting").has("active")).isFalse();
        assertThat(audit.at("/tables/0/picks").asInt()).isEqualTo(4);
        int picksPerDay = 0;
        for (JsonNode day : audit.path("activity")) picksPerDay += day.path("picks").asInt();
        assertThat(picksPerDay).isEqualTo(4);
        assertThat(audit.at("/betting/netCents").asLong()).isZero();
        assertThat(audit.at("/moderation/against/open").asInt()).isEqualTo(1);
        assertThat(audit.at("/money/paidCents").asLong()).isEqualTo(250);
        assertThat(audit.at("/tables/0/messages").asInt()).isEqualTo(2);
        assertThat(audit.at("/tables/0/polls").asInt()).isEqualTo(1);
        assertThat(audit.path("activity")).hasSize(30);
        // The audit never names a pick, so an Active bet's choice can't leak.
        assertThat(audit.toString()).doesNotContain("\"choice\"");

        // Opening it again right away isn't logged twice.
        read(get("/admin/users/" + player + "/audit"), admin);
        assertThat(logged(player, "view_user")).isEqualTo(1);

        // Searches escape LIKE wildcards and are logged; paging further isn't.
        JsonNode found = read(get("/admin/users/" + player + "/activity").param("q", "50%"), admin);
        assertThat(found.path("items")).hasSize(1);
        assertThat(found.at("/items/0/text").asText()).contains("50% line");
        assertThat(OpenApiSchemas.load("ActivityItem").validate(found.at("/items/0"))).isEmpty();
        assertThat(logged(player, "search_activity")).isEqualTo(1);
        JsonNode polls = read(get("/admin/users/" + player + "/activity").param("q", "pepperoni"), admin);
        assertThat(polls.at("/items/0/kind").asText()).isEqualTo("poll");
        JsonNode bets = read(get("/admin/users/" + player + "/activity").param("kind", "bet"), admin);
        // The test bets were proposed by the other player.
        assertThat(bets.path("items")).isEmpty();
        JsonNode everything = read(get("/admin/users/" + player + "/activity"), admin);
        assertThat(everything.path("items")).hasSize(3);

        JsonNode log = read(get("/admin/audit-log"), admin);
        assertThat(log.path("items").findValuesAsText("subjectName")).contains("aud_p");
        JsonNode theirs = read(get("/admin/audit-log").param("subject", player.toString()), admin);
        assertThat(theirs.path("items").findValuesAsText("subjectName")).containsOnly("aud_p");
        assertThat(theirs.at("/items/0/adminName").asText()).isEqualTo("aud_admin");

        mvc.perform(get("/admin/users/" + UUID.randomUUID() + "/audit").cookie(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/users/" + player + "/activity").param("kind", "nope").cookie(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/users/" + player + "/activity").param("cursor", "bad").cookie(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/users/" + player + "/audit").cookie(loginAs("aud_p")))
                .andExpect(status().isForbidden());
    }

    @Test
    void playersCanBeSearchedByUsernamePrefix() throws Exception {
        user("srch_one", "PLAYER");
        user("srch_two", "PLAYER");
        user("other_srch", "PLAYER");
        user("srch_admin", "ADMIN");
        Cookie admin = loginAs("srch_admin");
        JsonNode found = read(get("/admin/players").param("q", "srch_"), admin);
        assertThat(found.path("items").findValuesAsText("username")).containsExactlyInAnyOrder("srch_one", "srch_two");
        assertThat(found.at("/items/0/strikes").asInt()).isZero();
        // An underscore is literal, not a wildcard.
        assertThat(read(get("/admin/players").param("q", "srch1"), admin).path("items")).isEmpty();
    }

    @Test
    void feedbackInboxFiltersAndMarksDone() throws Exception {
        UUID player = user("fb_p", "PLAYER");
        user("fb_admin", "ADMIN");
        UUID bug = UUID.randomUUID();
        jdbc.update("INSERT INTO feedback (id, user_id, kind, text) VALUES (?, ?, 'bug', 'The ticker froze')",
                bug, player);
        jdbc.update("INSERT INTO feedback (id, user_id, kind, text) VALUES (?, ?, 'mode', 'Add parlays please')",
                UUID.randomUUID(), player);
        Cookie admin = loginAs("fb_admin");
        JsonNode bugs = read(get("/admin/feedback").param("kind", "bug"), admin);
        assertThat(bugs.path("items").findValuesAsText("text")).contains("The ticker froze");
        assertThat(bugs.path("items").findValuesAsText("kind")).containsOnly("bug");
        assertThat(OpenApiSchemas.load("FeedbackItem").validate(bugs.at("/items/0"))).isEmpty();

        write(put("/admin/feedback/" + bug), admin, "{\"status\":\"DONE\",\"note\":\"Fixed in poller\"}")
                .andExpect(status().isNoContent());
        JsonNode done = read(get("/admin/feedback").param("status", "done"), admin);
        JsonNode item = done.path("items").get(0);
        assertThat(item.path("id").asText()).isEqualTo(bug.toString());
        assertThat(item.path("adminNote").asText()).isEqualTo("Fixed in poller");
        assertThat(item.path("handledBy").asText()).isEqualTo("fb_admin");
        assertThat(read(get("/admin/feedback"), admin).path("items").findValuesAsText("id"))
                .doesNotContain(bug.toString());

        write(put("/admin/feedback/" + bug), admin, "{\"status\":\"NEW\"}").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT handled_by IS NULL FROM feedback WHERE id = ?", Boolean.class, bug))
                .isTrue();
        write(put("/admin/feedback/" + bug), admin, "{\"status\":\"MAYBE\"}").andExpect(status().isBadRequest());
        write(put("/admin/feedback/" + UUID.randomUUID()), admin, "{\"status\":\"DONE\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void perfRecordsServerAndBrowserTimingsByRoute() throws Exception {
        UUID player = user("perf_p", "PLAYER");
        user("perf_admin", "ADMIN");
        UUID table = table("Perf", player);
        Cookie session = loginAs("perf_p");
        mvc.perform(get("/tables/" + table).cookie(session)).andExpect(status().isOk());
        write(post("/perf"), session, """
                {"samples": [
                  {"kind": "api", "method": "GET", "path": "/tables/%s?x=1", "status": 200, "ms": 120},
                  {"kind": "api", "method": "GET", "path": "/no/such/thing", "status": 200, "ms": 5},
                  {"kind": "api", "method": "POST", "path": "/tables/%s/messages", "status": 0, "ms": 9000},
                  {"kind": "page", "path": "/tables/$tableId", "ms": 800},
                  {"kind": "page", "path": "/Not A Route", "ms": 800},
                  {"kind": "lcp", "path": "/", "ms": 1500}
                ]}
                """.formatted(table, table)).andExpect(status().isNoContent());
        write(post("/perf"), session, "{\"samples\": " + "[{\"kind\":\"lcp\",\"path\":\"/\",\"ms\":1}"
                + ",{\"kind\":\"lcp\",\"path\":\"/\",\"ms\":1}".repeat(100) + "]}").andExpect(status().isBadRequest());
        recorder.flushAll();

        Cookie admin = loginAs("perf_admin");
        JsonNode server = read(get("/admin/perf").param("window", "1h"), admin);
        assertThat(OpenApiSchemas.load("PerfReport").validate(server)).isEmpty();
        assertThat(server.path("series")).hasSizeGreaterThanOrEqualTo(60);
        assertThat(routes(server)).contains("GET /tables/{tableId}");
        JsonNode client = read(get("/admin/perf").param("source", "client").param("window", "6h"), admin);
        assertThat(routes(client)).contains("GET /tables/{tableId}", "POST /tables/{tableId}/messages",
                "PAGE /tables/$tableId", "LCP /").doesNotContain("GET /no/such/thing", "PAGE /Not A Route");
        JsonNode failed = read(get("/admin/perf").param("source", "client")
                .param("method", "POST").param("route", "/tables/{tableId}/messages"), admin);
        assertThat(failed.at("/totals/serverErrors").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(failed.at("/totals/p99").asInt()).isGreaterThan(5000);

        JsonNode live = read(get("/admin/perf/live"), admin);
        assertThat(OpenApiSchemas.load("PerfLive").validate(live)).isEmpty();
        assertThat(live.at("/jvm/threads").asInt()).isPositive();
        mvc.perform(get("/admin/perf").param("window", "2d").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/perf").cookie(session)).andExpect(status().isForbidden());
    }

    private static java.util.List<String> routes(JsonNode report) {
        java.util.List<String> routes = new java.util.ArrayList<>();
        report.path("routes").forEach(row -> routes.add(row.path("method").asText() + " " + row.path("route").asText()));
        return routes;
    }

    @Test
    void sportsExplorerWalksLeaguesDaysGamesAndHistory() throws Exception {
        user("sp_admin", "ADMIN");
        // Made-up teams: game rosters come from the players table by team, which other tests share.
        String game = "NBA-adm" + ThreadLocalRandom.current().nextInt(1_000_000);
        Instant start = Instant.parse("2026-03-04T00:30:00Z");
        jdbc.update("""
                INSERT INTO games (id, league, provider_id, status, starts_at, period, clock, home_abbreviation,
                                   home_name, home_city, home_score, away_abbreviation, away_name, away_city,
                                   away_score, home_periods, away_periods, observed_at)
                VALUES (?, 'NBA', ?, 'LIVE', ?, 2, '5:00', 'ZZH', 'Homers', 'Hometown', 50, 'ZZA', 'Aways',
                        'Awaytown', 48, ARRAY[25, 25], ARRAY[30, 18], now())
                """, game, game.substring(4), Timestamp.from(start));
        jdbc.update("""
                INSERT INTO game_snapshots (game_id, observed_at, status, period, clock, home_score, away_score,
                                            home_periods, away_periods)
                VALUES (?, now() - interval '1 minute', 'LIVE', 1, '0:00', 25, 30, ARRAY[25], ARRAY[30]),
                       (?, now(), 'LIVE', 2, '5:00', 50, 48, ARRAY[25, 25], ARRAY[30, 18])
                """, game, game);
        String player = "NBA-p" + ThreadLocalRandom.current().nextInt(1_000_000);
        jdbc.update("""
                INSERT INTO players (id, league, provider_id, first_name, last_name, position, team_abbreviation,
                                     active, updated_at)
                VALUES (?, 'NBA', ?, 'Jay', 'Tee', 'F', 'ZZH', true, now())
                """, player, player.substring(4));
        jdbc.update("""
                INSERT INTO player_game_stats (game_id, player_id, team_abbreviation, played, stats, observed_at)
                VALUES (?, ?, 'ZZH', true, '{"pts": 12}'::jsonb, now())
                """, game, player);
        jdbc.update("""
                INSERT INTO player_stat_snapshots (game_id, player_id, observed_at, played, stats)
                VALUES (?, ?, now(), true, '{"pts": 12}'::jsonb)
                """, game, player);

        Cookie admin = loginAs("sp_admin");
        JsonNode leagues = read(get("/admin/sports/leagues"), admin);
        assertThat(leagues.findValuesAsText("league")).containsExactly("NBA", "NFL");
        // 00:30 UTC on March 4 is the evening of March 3 in New York.
        JsonNode days = read(get("/admin/sports/days").param("league", "NBA"), admin);
        assertThat(days.findValuesAsText("date")).contains("2026-03-03");
        JsonNode games = read(get("/admin/sports/games").param("league", "NBA").param("date", "2026-03-03"), admin);
        assertThat(games.findValuesAsText("id")).contains(game);
        JsonNode detail = read(get("/admin/sports/games/" + game), admin);
        assertThat(OpenApiSchemas.load("SportsGameDetail").validate(detail)).isEmpty();
        assertThat(detail.at("/game/home/periods").toString()).isEqualTo("[25,25]");
        assertThat(detail.at("/players/0/stats/pts").asInt()).isEqualTo(12);
        JsonNode snapshots = read(get("/admin/sports/games/" + game + "/snapshots"), admin);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).path("period").asInt()).isEqualTo(1);
        assertThat(read(get("/admin/sports/games/" + game + "/players/" + player + "/snapshots"), admin))
                .hasSize(1);
        mvc.perform(get("/admin/sports/games/NBA-missing").cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/sports/days").param("league", "MLB").cookie(admin))
                .andExpect(status().isBadRequest());
    }
}

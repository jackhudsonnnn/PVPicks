package com.p2picks.app.demo;

import static com.p2picks.app.demo.DemoBots.LOCK_MARGIN;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.demo.BotWorld.OpenBet;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** What every bot planner shares: the scheduler ({@link BotKit}), the database, and the open bets query. */
abstract class BotPlanner {
    protected final BotKit kit;
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper json;
    protected final Map<UUID, DemoUser> bots;
    protected final Duration tick;
    protected final Set<String> decided;

    BotPlanner(BotKit kit) {
        this.kit = kit;
        this.jdbc = kit.jdbc;
        this.json = kit.json;
        this.bots = kit.bots;
        this.tick = kit.tick;
        this.decided = kit.decided;
    }

    /** Forgets what this planner remembers between ticks (after a reset). */
    void forget() {}

    protected Random random() {
        return kit.random();
    }

    protected void plan(Instant at, String what, Supplier<String> action) {
        kit.plan(at, what, action);
    }

    protected String act(UUID bot, Function<Jwt, String> action) {
        return kit.act(bot, action);
    }

    protected boolean decide(String what, UUID thing, UUID bot) {
        return kit.decide(what, thing, bot);
    }

    protected static String key() {
        return BotKit.key();
    }

    protected static String array(Collection<UUID> ids) {
        return BotKit.array(ids);
    }

    protected List<String> strings(String jsonArray) {
        return kit.strings(jsonArray);
    }

    /** Bets still open at these tables, a little before their lock (a pick that late wouldn't land). */
    protected List<OpenBet> openBets(Collection<UUID> tables, Instant now) {
        if (tables.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT id, table_id, mode, config ->> 'line' AS line, choices::text AS choices, locks_at FROM bets
                WHERE table_id = ANY (?::uuid[]) AND status = 'ACTIVE' AND locks_at > ?
                """, (rs, row) -> new OpenBet(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getString("mode"), rs.getString("line") == null ? null : new BigDecimal(rs.getString("line")),
                        strings(rs.getString("choices")), rs.getTimestamp("locks_at").toInstant()),
                array(tables), Timestamp.from(now.plus(LOCK_MARGIN)));
    }
}

package com.p2picks.app.demo;

import com.p2picks.app.security.SignInCodes;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wipes app data and seeds the demo world, keeping sports data. Also seeds an empty database on startup. Dev demo
 * only (docs/site/demo-data.md).
 */
@Service
@ConditionalOnProperty(name = "pvpicks.demo.enabled", havingValue = "true")
public class DemoReset {
    private static final Logger log = LoggerFactory.getLogger(DemoReset.class);

    /** Kept by a reset: the migration history and sports data, so the ticker isn't empty afterwards. */
    static final Set<String> KEPT = Set.of("flyway_schema_history", "games", "game_snapshots", "players",
            "player_game_stats", "player_stat_snapshots", "sports_feed_status",
            // Request timings describe the server, not the demo world.
            "perf_minutes");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final ObjectProvider<DemoBots> bots;
    private final ObjectProvider<DemoHistory> history;
    private final ObjectProvider<DemoGames> games;
    private final SignInCodes codes;

    DemoReset(JdbcTemplate jdbc, PlatformTransactionManager transactions, PasswordEncoder passwords, Clock clock,
            ObjectProvider<DemoBots> bots, ObjectProvider<DemoHistory> history, ObjectProvider<DemoGames> games,
            SignInCodes codes) {
        this.codes = codes;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.passwords = passwords;
        this.clock = clock;
        this.bots = bots;
        this.history = history;
        this.games = games;
    }

    /** What the reseeded world holds. */
    public record Summary(int users, int follows, int blocks, int tables) {}

    /**
     * Wipes every app table except {@link #KEPT} (sessions included, so everyone is signed out) and reseeds: the
     * world in one transaction, then the history through the services. The bots wait until it's done.
     */
    public synchronized Summary reset() {
        DemoBots running = bots.getIfAvailable();
        if (running != null) running.pause();
        try {
            Summary summary = transaction.execute(status -> wipeAndSeedWorld());
            // Every demo player gets a sign-in code, like real players (the admin's QR cards show them).
            codes.issueMissing();
            log.info("Demo world reset: {}", summary);
            // The pro's live score bets need the demo games on the board.
            games.ifAvailable(DemoGames::tick);
            DemoHistory seeder = history.getIfAvailable();
            if (seeder != null) {
                try {
                    log.info("Demo history seeded: {}", seeder.seed());
                } catch (RuntimeException exception) {
                    log.warn("Demo history seeding stopped partway; the world is usable without it", exception);
                }
            }
            return summary;
        } finally {
            // The bots' sessions, plans and decisions refer to what was just wiped.
            if (running != null) {
                running.forget();
                running.resume();
            }
        }
    }

    private Summary wipeAndSeedWorld() {
        List<String> wiped = jdbc.queryForList("""
                SELECT quote_ident(tablename) FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
                """, String.class).stream().filter(table -> !KEPT.contains(table)).toList();
        // Ledger and settlement tables refuse TRUNCATE (append-only triggers). Their user triggers are off only
        // inside this transaction; foreign keys are internal triggers and stay on.
        List<String> guarded = jdbc.queryForList("""
                SELECT DISTINCT quote_ident(c.relname) FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND NOT t.tgisinternal AND (t.tgtype & 32) <> 0
                """, String.class).stream().filter(wiped::contains).toList();
        guarded.forEach(table -> jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER"));
        // No CASCADE: if a kept table ever references a wiped one, this fails instead of wiping sports data.
        jdbc.execute("TRUNCATE " + String.join(", ", wiped));
        guarded.forEach(table -> jdbc.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER"));
        jdbc.execute("DROP SCHEMA IF EXISTS p2picks_dev_seed CASCADE");
        return new DemoWorld(jdbc, passwords, clock).seed();
    }

    /** A fresh database (no users yet) gets the demo world on startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedEmptyDatabase() {
        if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users)", Boolean.class))) {
            log.info("Empty database: seeding the demo world");
            reset();
        }
    }
}

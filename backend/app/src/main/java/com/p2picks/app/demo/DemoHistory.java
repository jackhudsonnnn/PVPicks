package com.p2picks.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.bets.BetLockService;
import com.p2picks.app.bets.BetPickService;
import com.p2picks.app.bets.SportsProposalService;
import com.p2picks.app.bets.SportsResolutionService;
import com.p2picks.app.bets.U2PickDefinition;
import com.p2picks.app.bets.U2PickProposalService;
import com.p2picks.app.bets.U2PickResolutionService;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.moderation.ReportService;
import com.p2picks.app.security.PasswordlessSignIn;
import com.p2picks.app.settlement.DebtCapService;
import com.p2picks.app.settlement.SettleRules;
import com.p2picks.app.settlement.SettleService;
import com.p2picks.app.settlement.SettlementReviewService;
import com.p2picks.app.settlement.TablePointsSettlementService;
import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.League;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The main accounts' past, played out through the real services and then backdated: the pro's ~300 tickets over
 * 90 days with points settlements and receipts, the intermediate's handful, the pro's 20 Pending bets, and open
 * reports for the admin. Going through the services keeps bets, picks, ledger, settlements and receipts consistent
 * by construction; only timestamps are rewritten afterwards. See docs/site/demo-data.md.
 */
@Component
@ConditionalOnProperty(name = "pvpicks.demo.enabled", havingValue = "true")
public class DemoHistory {
    private static final Logger log = LoggerFactory.getLogger(DemoHistory.class);
    static final int PRO_DAYS = 90;
    static final int PRO_PENDING = 20;
    static final int INTERMEDIATE_TICKETS = 8;
    private static final int SETTLE_EVERY_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;
    private final PasswordlessSignIn signIn;
    private final GameSync games;
    private final U2PickProposalService u2picks;
    private final SportsProposalService sportsBets;
    private final BetPickService picks;
    private final BetLockService locks;
    private final U2PickResolutionService u2pickResults;
    private final SportsResolutionService sportsResults;
    private final TablePointsSettlementService points;
    private final SettleService settles;
    private final SettlementReviewService reviews;
    private final DebtCapService caps;
    private final ReportService reports;
    private final Map<UUID, DemoUser> bots = DemoRoster.users().stream()
            .collect(Collectors.toMap(DemoUser::id, Function.identity()));

    DemoHistory(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json, Clock clock,
            PasswordlessSignIn signIn, GameSync games, U2PickProposalService u2picks,
            SportsProposalService sportsBets, BetPickService picks, BetLockService locks,
            U2PickResolutionService u2pickResults, SportsResolutionService sportsResults,
            TablePointsSettlementService points, SettleService settles, SettlementReviewService reviews,
            DebtCapService caps, ReportService reports) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.json = json;
        this.clock = clock;
        this.signIn = signIn;
        this.games = games;
        this.u2picks = u2picks;
        this.sportsBets = sportsBets;
        this.picks = picks;
        this.locks = locks;
        this.u2pickResults = u2pickResults;
        this.sportsResults = sportsResults;
        this.points = points;
        this.settles = settles;
        this.reviews = reviews;
        this.caps = caps;
        this.reports = reports;
    }

    /** What was seeded, for the log and tests. */
    public record Summary(int proTickets, int intermediateTickets, int proPending, int settlements, int reports) {}

    /** Seeds the history into a freshly seeded world. Each step is its own transaction, as live requests are. */
    public Summary seed() {
        Run run = new Run(clock.instant());
        run.uncap(List.of(DemoWorld.PRO, DemoWorld.INTERMEDIATE));
        run.history(DemoWorld.PRO, PRO_DAYS, 3.6, Integer.MAX_VALUE);
        run.history(DemoWorld.INTERMEDIATE, 45, 1.0, INTERMEDIATE_TICKETS);
        run.settleUp(DemoWorld.INTERMEDIATE);
        run.backdate();
        int pending = run.pending(DemoWorld.PRO, PRO_PENDING);
        int filed = run.reports();
        return new Summary(run.tickets(DemoWorld.PRO), run.tickets(DemoWorld.INTERMEDIATE), pending,
                run.settlements.size(), filed);
    }

    private record TableInfo(UUID id, UUID host, Instant createdAt, List<UUID> members) {}

    private record Played(UUID bet, Instant createdAt, Instant locksAt, Instant closedAt) {}

    /** One seeding run: its random source, sessions, and what to backdate at the end. */
    private final class Run {
        private final Instant now;
        private final Random random = new Random(DemoRoster.SEED + 2);
        private final Map<UUID, Jwt> sessions = new HashMap<>();
        private final List<Played> played = new ArrayList<>();
        private final Map<UUID, Instant> settlements = new LinkedHashMap<>();
        private final Map<UUID, Instant> lineTimes = new HashMap<>();
        /** Chat messages written while playing a day, by message order range, and when that day closed. */
        private final List<long[]> messageRanges = new ArrayList<>();
        private final List<Instant> messageCloses = new ArrayList<>();
        private int gameNumber;

        Run(Instant now) {
            this.now = now;
        }

        Jwt as(UUID user) {
            return sessions.computeIfAbsent(user, signIn::session);
        }

        String key() {
            return UUID.randomUUID().toString();
        }

        /** No debt caps for the main accounts and everyone they play with, so a losing streak can't stop the story. */
        void uncap(List<UUID> users) {
            Set<UUID> everyone = new HashSet<>(users);
            for (UUID user : users) {
                for (TableInfo table : tables(user)) everyone.addAll(table.members());
            }
            for (UUID user : everyone) caps.replace(as(user), key(), null);
        }

        /** The user's tables, except Mosh Pit: it has no seeded past, so the pro's story stays as documented. */
        List<TableInfo> tables(UUID user) {
            List<TableInfo> tables = new ArrayList<>();
            jdbc.query("""
                    SELECT t.id, t.created_at,
                           (SELECT h.user_id FROM table_members h WHERE h.table_id = t.id AND h.role = 'HOST'
                            ORDER BY h.joined_at LIMIT 1) AS host
                    FROM tables t JOIN table_members m ON m.table_id = t.id
                    WHERE m.user_id = ? AND t.archived_at IS NULL AND t.id <> ? ORDER BY t.created_at
                    """, rs -> {
                        UUID id = rs.getObject("id", UUID.class);
                        tables.add(new TableInfo(id, rs.getObject("host", UUID.class),
                                rs.getTimestamp("created_at").toInstant(), jdbc.queryForList(
                                        "SELECT user_id FROM table_members WHERE table_id = ? AND can_bet",
                                        UUID.class, id)));
                    }, user, DemoWorld.CROWD_TABLE);
            return tables;
        }

        Instant evening(int daysAgo) {
            LocalDate day = LocalDate.ofInstant(now, BotMind.NEW_YORK).minusDays(daysAgo);
            return day.atTime(LocalTime.of(18, 0)).atZone(BotMind.NEW_YORK).toInstant()
                    .plus(Duration.ofMinutes(random.nextInt(300)));
        }

        long lastMessage() {
            Long order = jdbc.queryForObject("SELECT max(message_order) FROM chat_messages", Long.class);
            return order == null ? 0 : order;
        }

        /** {@code perDay} tickets a day for {@code days} days (at most {@code limit}), each in one of the user's tables. */
        void history(UUID user, int days, double perDay, int limit) {
            List<TableInfo> tables = tables(user);
            int made = 0;
            for (int daysAgo = days; daysAgo >= 1 && made < limit; daysAgo--) {
                Instant evening = evening(daysAgo);
                List<TableInfo> open = tables.stream().filter(t -> t.createdAt().isBefore(evening)).toList();
                int count = perDay >= 1 ? (int) Math.floor(perDay) + (random.nextDouble() < perDay % 1 ? 1 : 0)
                        : random.nextDouble() < perDay ? 1 : 0;
                if (limit < Integer.MAX_VALUE) count = random.nextInt(days) < limit ? 1 : 0;
                if (open.isEmpty() || count == 0) continue;
                long firstMessage = lastMessage();
                Game game = random.nextDouble() < 0.6 ? game(evening) : null;
                List<UUID> sportsBets = new ArrayList<>();
                List<UUID[]> u2pickBets = new ArrayList<>();
                Instant closed = evening;
                for (int i = 0; i < count && made < limit; i++) {
                    TableInfo table = DemoLines.any(random, open);
                    Instant createdAt = evening.plus(Duration.ofMinutes(i * 25L + random.nextInt(20)));
                    Duration window = Duration.ofSeconds(120 + random.nextInt(481));
                    boolean sports = game != null && random.nextDouble() < 0.5;
                    UUID bet = play(user, table, sports ? game : null);
                    if (bet == null) continue;
                    made++;
                    Instant locksAt = createdAt.plus(window);
                    Instant closesAt = sports ? game.finalAt().plus(Duration.ofMinutes(20))
                            : locksAt.plus(Duration.ofMinutes(30 + random.nextInt(150)));
                    played.add(new Played(bet, createdAt, locksAt, closesAt));
                    if (closesAt.isAfter(closed)) closed = closesAt;
                    if (sports) sportsBets.add(bet);
                    else u2pickBets.add(new UUID[] {bet, table.host()});
                }
                if (game != null) finish(game);
                for (UUID bet : sportsBets) sportsResults.resolve(bet, now);
                for (UUID[] bet : u2pickBets) resolveU2Pick(bet[0], bet[1]);
                if (user.equals(DemoWorld.PRO)) {
                    for (int t = 0; t < open.size(); t++) {
                        if ((daysAgo + t * 3) % SETTLE_EVERY_DAYS == 0) settle(open.get(t), closed.plusSeconds(600), daysAgo);
                    }
                }
                messageRanges.add(new long[] {firstMessage, lastMessage()});
                messageCloses.add(closed);
            }
        }

        /** A ticket for {@code user}: someone at the table proposes, the user and some table-mates pick, it locks. */
        UUID play(UUID user, TableInfo table, Game game) {
            // Only the user and demo users: one main account's story doesn't spill into another's.
            List<UUID> members = table.members().stream()
                    .filter(member -> member.equals(user) || bots.containsKey(member)).toList();
            UUID proposer = random.nextDouble() < 0.25 || members.size() < 2 ? user : DemoLines.any(random, members);
            long wager = 25L * (2 + random.nextInt(19));
            UUID bet;
            List<String> choices;
            String mode;
            BigDecimal line = null;
            try {
                if (game != null) {
                    boolean total = random.nextBoolean();
                    mode = total ? "total_disaster" : "give_and_take";
                    line = total ? BigDecimal.valueOf(game.total() + random.nextInt(21) - 10).add(new BigDecimal("0.5"))
                            : BigDecimal.valueOf(1 + random.nextInt(8)).add(new BigDecimal("0.5"))
                                    .multiply(BigDecimal.valueOf(random.nextBoolean() ? 1 : -1));
                    var proposal = new SportsProposalService.Proposal(mode, game.id(), line, false, wager, 300, false);
                    bet = sportsBets.create(as(proposer), table.id(), proposal, key()).betId();
                } else {
                    mode = "u2pick";
                    DemoLines.Question question = DemoLines.any(random, DemoLines.QUESTIONS);
                    var proposal = new U2PickProposalService.Proposal(
                            new U2PickDefinition(question.question(), question.choices()), json.createObjectNode(),
                            wager, 300, false, null);
                    bet = u2picks.create(as(proposer), table.id(), proposal, key()).betId();
                }
            } catch (RuntimeException refused) {
                log.debug("Demo history proposal refused: {}", refused.toString());
                return null;
            }
            if (bet == null) return null;
            try {
                choices = json.readValue(jdbc.queryForObject("SELECT choices::text FROM bets WHERE id = ?",
                        String.class, bet), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
                throw new IllegalStateException(unreadable);
            }
            Map<String, Integer> counts = new HashMap<>();
            List<UUID> pickers = new ArrayList<>(List.of(user));
            for (UUID member : members) {
                if (member.equals(user)) continue;
                DemoUser bot = bots.get(member);
                double chance = bot == null ? 0.4 : bot.profile().joinBetChance();
                if (random.nextDouble() < chance) pickers.add(member);
            }
            for (UUID picker : pickers) {
                DemoUser bot = bots.get(picker);
                String choice = BotMind.pick(random, bot == null ? DemoProfile.PickStyle.RANDOM
                        : bot.profile().pickStyle(), mode, line, choices, counts);
                try {
                    if (picks.change(as(picker), bet, choice, key()) == BetPickService.Outcome.UPDATED) {
                        counts.merge(choice, 1, Integer::sum);
                    }
                } catch (RuntimeException refused) {
                    log.debug("Demo history pick refused: {}", refused.toString());
                }
            }
            locks.lockNow(bet);
            return bet;
        }

        void resolveU2Pick(UUID bet, UUID host) {
            // One-sided or empty bets already washed at lock (domain rules, "Wash triggers").
            if (!"PENDING".equals(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet))) {
                return;
            }
            List<String> picked = jdbc.queryForList("SELECT choice FROM picks WHERE bet_id = ?", String.class, bet);
            if (picked.isEmpty()) return;
            u2pickResults.resolve(as(host), bet, DemoLines.any(random, picked), key());
        }

        /** The host settles the table's points; older settle-ups are mostly paid, the latest stay open. */
        void settle(TableInfo table, Instant at, int daysAgo) {
            try {
                UUID settlement = points.settle(as(table.host()), table.id(), key()).settlementId();
                if (settlement == null) return;
                settlements.put(settlement, at);
                if (daysAgo > 7) settleLines(settlement, at, false);
            } catch (RuntimeException nothingToSettle) {
                log.debug("Demo history settlement skipped: {}", nothingToSettle.toString());
            }
        }

        /** The intermediate's own table settles once, and everything he's in gets paid: a tidy, short history. */
        void settleUp(UUID user) {
            for (TableInfo table : tables(user)) {
                if (table.host().equals(user)) settle(table, now.minus(Duration.ofDays(2)), 30);
            }
            List<UUID> open = jdbc.queryForList("""
                    SELECT DISTINCT settlement_id FROM settlement_lines
                    WHERE (payer_id = ? OR payee_id = ?) AND status = 'unpaid'
                    """, UUID.class, user, user);
            for (UUID settlement : open) {
                settleLines(settlement, settlements.getOrDefault(settlement, now.minus(Duration.ofDays(2))), true);
            }
        }

        private record Line(UUID id, UUID payer, UUID payee) {}

        void settleLines(UUID settlement, Instant at, boolean allPaid) {
            List<Line> lines = jdbc.query("""
                    SELECT id, payer_id, payee_id FROM settlement_lines WHERE settlement_id = ? AND status = 'unpaid'
                    """, (rs, row) -> new Line(rs.getObject("id", UUID.class), rs.getObject("payer_id", UUID.class),
                            rs.getObject("payee_id", UUID.class)), settlement);
            for (Line line : lines) {
                boolean intermediate = line.payer().equals(DemoWorld.INTERMEDIATE)
                        || line.payee().equals(DemoWorld.INTERMEDIATE);
                double roll = random.nextDouble();
                try {
                    // Paid ones are recorded by the person paid (final at once); some are forgiven.
                    if (allPaid || intermediate || roll < 0.8) {
                        settle(line.payee(), line.payer(), line.id(), SettleRules.Kind.RECEIVED);
                    } else if (roll < 0.9) {
                        settle(line.payee(), line.payer(), line.id(), SettleRules.Kind.FORGIVEN);
                    }
                    // Closed a few hours to a few days after the settlement, but never later than now.
                    Instant closed = at.plus(Duration.ofHours(2 + random.nextInt(70)));
                    Instant latest = now.minus(Duration.ofMinutes(5));
                    lineTimes.put(line.id(), closed.isAfter(latest) ? latest : closed);
                } catch (RuntimeException refused) {
                    log.debug("Demo history receipt refused: {}", refused.toString());
                }
            }
        }

        void settle(UUID actor, UUID other, UUID line, SettleRules.Kind kind) {
            try {
                settles.create(as(actor), other, kind, List.of(line), key());
            } catch (SettlementReviewService.ReviewRequired required) {
                List<UUID> pending = jdbc.queryForList("""
                        SELECT revision FROM settlement_pair_reviews
                        WHERE user_id = ? AND other_user_id = ? AND reviewed_at IS NULL
                        """, UUID.class, actor, other);
                if (!pending.isEmpty()) reviews.acknowledge(as(actor), other, pending.getFirst(), key());
                settles.create(as(actor), other, kind, List.of(line), key());
            }
        }

        private record Game(String id, League league, String providerId, Instant tipOff, Instant finalAt,
                int home, int away, String homeTeam, String awayTeam) {
            int total() {
                return home + away;
            }
        }

        /** A past game, live while that evening's bets are made; it goes final once they're locked. */
        Game game(Instant evening) {
            boolean nba = random.nextDouble() < 0.7;
            League league = nba ? League.NBA : League.NFL;
            String providerId = "DEV-history-" + (++gameNumber);
            int home = nba ? 95 + random.nextInt(40) : 10 + random.nextInt(25);
            int away = nba ? 95 + random.nextInt(40) : 10 + random.nextInt(25);
            if (home == away) home++;
            String[] teams = nba ? new String[] {"BOS", "LAL", "DEN", "MIL", "GSW", "NYK"}
                    : new String[] {"KC", "BUF", "PHI", "SF", "DET", "BAL"};
            int h = random.nextInt(teams.length);
            int a = (h + 1 + random.nextInt(teams.length - 1)) % teams.length;
            Instant tipOff = evening.minus(Duration.ofMinutes(30));
            Game game = new Game(league + "-" + providerId, league, providerId, tipOff,
                    tipOff.plus(Duration.ofMinutes(nba ? 140 : 190)), home, away, teams[h], teams[a]);
            games.recordDev(league, List.of(feed(game, false)), tipOff.plus(Duration.ofMinutes(20)));
            return game;
        }

        void finish(Game game) {
            games.recordDev(game.league(), List.of(feed(game, true)), game.finalAt());
        }

        FeedGame feed(Game game, boolean finished) {
            return new FeedGame(game.league(), game.providerId(), finished ? GameStatus.FINAL : GameStatus.LIVE,
                    game.tipOff(), finished ? 4 : 1, finished ? null : "8:00",
                    team(game.homeTeam(), finished ? game.home() : 0), team(game.awayTeam(), finished ? game.away() : 0),
                    null);
        }

        FeedGame.Team team(String abbreviation, int score) {
            int quarter = score / 4;
            return new FeedGame.Team(abbreviation, abbreviation, "", score,
                    score == 0 ? List.of(0) : List.of(quarter, quarter, quarter, score - 3 * quarter));
        }

        /** {@code count} Pending bets for the user, locked over the last few hours in his tables. */
        int pending(UUID user, int count) {
            List<TableInfo> tables = tables(user);
            List<String> live = jdbc.queryForList("""
                    SELECT id FROM games WHERE provider_id LIKE 'DEV-demo-%' AND status = 'LIVE'
                    """, String.class);
            int made = 0;
            // Bets where everyone picked the same side wash at lock, so it takes a few more tries to get there.
            for (int i = 0; i < count * 6 && made < count; i++) {
                TableInfo table = DemoLines.any(random, tables);
                Game game = null;
                if (!live.isEmpty() && made % 3 == 0) {
                    String id = DemoLines.any(random, live);
                    Integer total = jdbc.queryForObject(
                            "SELECT coalesce(home_score, 0) + coalesce(away_score, 0) FROM games WHERE id = ?",
                            Integer.class, id);
                    League league = League.valueOf(id.substring(0, 3));
                    // Roughly where the game will end: what's scored so far plus a typical rest of the game.
                    game = new Game(id, league, null, now, now, total + (league == League.NBA ? 110 : 20), 0, "", "");
                }
                UUID bet = play(user, table, game);
                if (bet == null) continue;
                // Washed at lock (one side only): it stays as a recent wash and doesn't count.
                if (!"PENDING".equals(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet))) {
                    continue;
                }
                made++;
                Instant createdAt = now.minus(Duration.ofMinutes(20 + random.nextInt(280)));
                Instant locksAt = createdAt.plus(Duration.ofSeconds(120 + random.nextInt(480)));
                transaction.executeWithoutResult(status -> jdbc.update("""
                        UPDATE bets SET created_at = ?, locks_at = ?, locked_at = ? WHERE id = ?
                        """, Timestamp.from(createdAt), Timestamp.from(locksAt), Timestamp.from(locksAt), bet));
            }
            return made;
        }

        /** A few open reports for the admin: troublemakers reported by people who share a table with them. */
        int reports() {
            String[][] notes = {
                {"harassment", "Keeps trash talking me after every bet."},
                {"spam", "Posts the same GIF over and over."},
                {"settlement", "Says they paid, never did."},
                {"offensive", "Language in the table chat."},
                {"other", "Something feels off about this account."},
                {"harassment", "Won't stop pinging the table."}};
            List<UUID[]> pairs = jdbc.query("""
                    SELECT a.user_id AS reporter, b.user_id AS target, a.table_id FROM table_members a
                    JOIN table_members b ON b.table_id = a.table_id AND b.user_id <> a.user_id
                    WHERE a.user_id <> ALL (?::uuid[]) AND b.user_id <> ALL (?::uuid[])
                    ORDER BY a.table_id, a.user_id, b.user_id
                    """, (rs, row) -> new UUID[] {rs.getObject("reporter", UUID.class),
                            rs.getObject("target", UUID.class), rs.getObject("table_id", UUID.class)},
                    "{" + DemoWorld.ADMIN + "," + DemoWorld.PRO + "," + DemoWorld.INTERMEDIATE + "," + DemoWorld.NEWBIE + "}",
                    "{" + DemoWorld.ADMIN + "," + DemoWorld.NEWBIE + "}");
            List<UUID[]> trolls = pairs.stream().filter(pair -> bots.containsKey(pair[1])
                    && bots.get(pair[1]).archetype() == DemoProfile.Archetype.TROLL).toList();
            List<UUID[]> from = trolls.isEmpty() ? pairs : trolls;
            int filed = 0;
            Set<UUID> reporters = new HashSet<>();
            for (int i = 0; i < from.size() && filed < notes.length; i++) {
                UUID[] pair = from.get(random.nextInt(from.size()));
                if (!reporters.add(pair[0])) continue;
                String[] note = notes[filed];
                var outcome = reports.submit(as(pair[0]), key(),
                        new ReportService.Submission(pair[1], note[0], note[1], pair[2], null));
                if (outcome == ReportService.Outcome.CREATED) filed++;
            }
            return filed;
        }

        int tickets(UUID user) {
            return jdbc.queryForObject("""
                    SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id
                    WHERE p.user_id = ? AND b.status IN ('SETTLED', 'WASHED')
                    """, Integer.class, user);
        }

        /**
         * Rewrites timestamps so the story happened when it says it did. Chat positions and receipt lines are
         * normally immutable; their guards are off only inside this transaction (dev demo only).
         */
        void backdate() {
            transaction.executeWithoutResult(status -> {
                jdbc.execute("ALTER TABLE chat_messages DISABLE TRIGGER chat_messages_position_immutable");
                jdbc.execute("ALTER TABLE settlement_lines DISABLE TRIGGER settlement_lines_identity_immutable");
                jdbc.execute("ALTER TABLE settles DISABLE TRIGGER settles_protected");
                jdbc.batchUpdate("""
                        UPDATE bets SET created_at = ?, locks_at = ?,
                            locked_at = CASE WHEN locked_at IS NULL THEN NULL ELSE ?::timestamptz END,
                            closed_at = CASE WHEN closed_at IS NULL THEN NULL ELSE ?::timestamptz END
                        WHERE id = ?
                        """, played.stream().map(p -> new Object[] {Timestamp.from(p.createdAt()),
                                Timestamp.from(p.locksAt()), Timestamp.from(p.locksAt()),
                                Timestamp.from(p.closedAt()), p.bet()}).toList());
                jdbc.batchUpdate("""
                        UPDATE picks SET picked_at = ?::timestamptz + (random() * (?::timestamptz - ?::timestamptz))
                        WHERE bet_id = ?
                        """, played.stream().map(p -> new Object[] {Timestamp.from(p.createdAt()),
                                Timestamp.from(p.locksAt()), Timestamp.from(p.createdAt()), p.bet()}).toList());
                // A day's chat: bet cards when their bet was made, everything else when the day's bets closed.
                for (int i = 0; i < messageRanges.size(); i++) {
                    long[] range = messageRanges.get(i);
                    jdbc.update("""
                            UPDATE chat_messages m SET created_at = coalesce(
                                (SELECT b.created_at FROM bets b WHERE b.id = m.bet_id AND m.kind = 'bet'),
                                ?::timestamptz)
                            WHERE m.message_order > ? AND m.message_order <= ?
                            """, Timestamp.from(messageCloses.get(i)), range[0], range[1]);
                }
                for (Map.Entry<UUID, Instant> settlement : settlements.entrySet()) {
                    Timestamp at = Timestamp.from(settlement.getValue());
                    jdbc.update("UPDATE settlement_lines SET created_at = ?, updated_at = ? WHERE settlement_id = ?",
                            at, at, settlement.getKey());
                    jdbc.update("""
                            UPDATE bets SET points_settled_at = ?
                            WHERE id IN (SELECT bet_id FROM settlement_bets WHERE settlement_id = ?)
                            """, at, settlement.getKey());
                    jdbc.update("UPDATE chat_messages SET created_at = ? WHERE settlement_id = ?", at, settlement.getKey());
                }
                jdbc.batchUpdate("UPDATE settlement_lines SET updated_at = ? WHERE id = ? AND status <> 'unpaid'",
                        lineTimes.entrySet().stream().map(e -> new Object[] {Timestamp.from(e.getValue()), e.getKey()})
                                .toList());
                // Each closed receipt's settle happened when the receipt closed.
                jdbc.batchUpdate("""
                        UPDATE settles SET created_at = ? WHERE id = (SELECT settle_id FROM settlement_lines WHERE id = ?)
                        """, lineTimes.entrySet().stream()
                                .map(e -> new Object[] {Timestamp.from(e.getValue()), e.getKey()}).toList());
                jdbc.execute("ALTER TABLE chat_messages ENABLE TRIGGER chat_messages_position_immutable");
                jdbc.execute("ALTER TABLE settlement_lines ENABLE TRIGGER settlement_lines_identity_immutable");
                jdbc.execute("ALTER TABLE settles ENABLE TRIGGER settles_protected");
            });
        }
    }
}

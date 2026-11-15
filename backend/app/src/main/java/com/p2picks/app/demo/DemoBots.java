package com.p2picks.app.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.bets.BetPickService;
import com.p2picks.app.bets.SportsProposalService;
import com.p2picks.app.bets.U2PickDefinition;
import com.p2picks.app.bets.U2PickProposalService;
import com.p2picks.app.bets.U2PickResolutionService;
import com.p2picks.app.bets.U2PickSettler;
import com.p2picks.app.chat.ChatSendService;
import com.p2picks.app.chat.GifCatalog;
import com.p2picks.app.chat.ReactionService;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.moderation.ReportService;
import com.p2picks.app.security.PasswordlessSignIn;
import com.p2picks.app.settlement.SettlementReceiptService;
import com.p2picks.app.settlement.SettlementReviewService;
import com.p2picks.app.settlement.TablePointsSettlementService;
import com.p2picks.app.social.InvitationResponseService;
import com.p2picks.app.social.SocialMutationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The demo users, acting. Every few seconds the loop looks at the tables that include a main account (newbie,
 * intermediate, pro) and lets online demo users act there, per their {@link DemoProfile}, through the same services
 * real requests use: each bot has a real session and every rule applies to it. Decisions are made once per thing
 * (a bet, a follow, an invitation) and carried out after the bot's reaction delay. In-memory: a backend restart or
 * a reset starts the bots over. See docs/site/demo-data.md.
 */
@Component
@ConditionalOnExpression("${pvpicks.demo.enabled:false} and ${pvpicks.demo.bots.enabled:true}")
public class DemoBots {
    private static final Logger log = LoggerFactory.getLogger(DemoBots.class);
    private static final List<String> MAIN_PLAYERS = List.of("newbie", "intermediate", "pro");
    /** The pro is kept near this many open (Active) bets in his tables, and this many Pending bets of his own. */
    static final int PRO_OPEN = 5;
    static final int PRO_PENDING = 20;
    private static final Duration LOCK_MARGIN = Duration.ofSeconds(3);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration tick;
    private final PasswordlessSignIn signIn;
    private final BetPickService picks;
    private final U2PickProposalService u2picks;
    private final SportsProposalService sportsBets;
    private final U2PickResolutionService results;
    private final SocialMutationService social;
    private final InvitationResponseService invitations;
    private final ChatSendService chat;
    private final ReactionService reactions;
    private final GifCatalog gifs;
    private final ReportService reports;
    private final SettlementReceiptService receipts;
    private final SettlementReviewService reviews;
    private final TablePointsSettlementService pointsSettlement;

    private final Map<UUID, DemoUser> bots = DemoRoster.users().stream()
            .collect(Collectors.toMap(DemoUser::id, Function.identity()));
    private Random random = new Random();
    private final Map<UUID, Jwt> sessions = new HashMap<>();
    private final Set<String> decided = new HashSet<>();
    private final PriorityQueue<Planned> queue = new PriorityQueue<>((a, b) -> a.at().compareTo(b.at()));
    private final Map<UUID, UUID> trashTalk = new LinkedHashMap<>();
    private final Map<String, Integer> done = new java.util.TreeMap<>();
    private Instant since;
    private Instant lastTick;
    private volatile boolean paused;

    private record Planned(Instant at, String what, Runnable action) {}

    DemoBots(JdbcTemplate jdbc, ObjectMapper json, Clock clock,
            @Value("${pvpicks.demo.bots.tick:PT5S}") Duration tick, PasswordlessSignIn signIn, BetPickService picks,
            U2PickProposalService u2picks, SportsProposalService sportsBets, U2PickResolutionService results,
            SocialMutationService social, InvitationResponseService invitations, ChatSendService chat,
            ReactionService reactions, GifCatalog gifs, ReportService reports, SettlementReceiptService receipts,
            SettlementReviewService reviews, TablePointsSettlementService pointsSettlement) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tick = tick;
        this.signIn = signIn;
        this.picks = picks;
        this.u2picks = u2picks;
        this.sportsBets = sportsBets;
        this.results = results;
        this.social = social;
        this.invitations = invitations;
        this.chat = chat;
        this.reactions = reactions;
        this.gifs = gifs;
        this.reports = reports;
        this.receipts = receipts;
        this.reviews = reviews;
        this.pointsSettlement = pointsSettlement;
        this.since = clock.instant();
    }

    /** What the bots have done since they started (or the last reset), for the Dev page. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("since", since.toString());
        status.put("lastTick", lastTick == null ? null : lastTick.toString());
        status.put("queued", queue.size());
        status.put("done", Map.copyOf(done));
        return status;
    }

    /** Stops acting (a reset is rewriting the world); planned actions wait. */
    void pause() {
        paused = true;
    }

    void resume() {
        paused = false;
    }

    /** Fixes the random source, so a test's decisions repeat. */
    synchronized void reseed(long seed) {
        random = new Random(seed);
    }

    /** Drops sessions, plans and decisions; called after a reset, when none of them mean anything. */
    synchronized void forget() {
        sessions.clear();
        decided.clear();
        queue.clear();
        trashTalk.clear();
        done.clear();
        since = clock.instant();
    }

    @Scheduled(fixedDelayString = "${pvpicks.demo.bots.tick:PT5S}",
            initialDelayString = "${pvpicks.demo.bots.initial-delay:PT20S}")
    public synchronized void tick() {
        if (paused) return;
        Instant now = clock.instant();
        lastTick = now;
        try {
            runDue(now);
            World world = world(now);
            if (world.members.isEmpty() && world.botIds.isEmpty()) return;
            planPicks(world, now);
            planProposals(world, now);
            planResults(world, now);
            planChat(world, now);
            planReactions(world, now);
            planTrashTalk(world, now);
            planComplaints(world, now);
            planSocial(world, now);
            planReceipts(world, now);
            planPointsSettlement(world, now);
        } catch (RuntimeException exception) {
            log.warn("Demo bots tick failed", exception);
        }
        if (decided.size() > 200_000) decided.clear();
    }

    // ---------------------------------------------------------------- the world as the bots see it

    private record Member(UUID table, UUID user, boolean host, boolean canBet, boolean canText, boolean canReact,
            boolean canGif) {}

    private record Table(UUID id, boolean hostsOnlyBets, boolean u2pickEnabled) {}

    private record World(UUID pro, Set<UUID> mainIds, Map<UUID, Table> tables, List<Member> members,
            Set<UUID> proTables, Set<UUID> botIds) {
        List<Member> botsIn(UUID table, Map<UUID, DemoUser> bots) {
            return members.stream().filter(m -> m.table().equals(table) && bots.containsKey(m.user())).toList();
        }
    }

    private World world(Instant now) {
        Map<String, UUID> main = new HashMap<>();
        jdbc.query("SELECT id, username::text AS name FROM users WHERE username::text = ANY (?::text[]) AND status = 'ACTIVE'",
                rs -> { main.put(rs.getString("name"), rs.getObject("id", UUID.class)); },
                "{" + String.join(",", MAIN_PLAYERS) + "}");
        Set<UUID> mainIds = new HashSet<>(main.values());
        Set<UUID> botIds = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users WHERE id = ANY (?::uuid[]) AND status = 'ACTIVE'", UUID.class, array(bots.keySet())));
        Map<UUID, Table> tables = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT t.id, t.hosts_only_bets, t.u2pick_enabled FROM tables t
                JOIN table_members m ON m.table_id = t.id
                WHERE t.archived_at IS NULL AND m.user_id = ANY (?::uuid[])
                """, rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    tables.put(id, new Table(id, rs.getBoolean("hosts_only_bets"), rs.getBoolean("u2pick_enabled")));
                }, array(mainIds));
        List<Member> members = tables.isEmpty() ? List.of() : jdbc.query("""
                SELECT table_id, user_id, role, can_bet, can_text, can_react, can_send_gifs FROM table_members
                WHERE table_id = ANY (?::uuid[])
                """, (rs, row) -> new Member(rs.getObject("table_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("role").equals("HOST"), rs.getBoolean("can_bet"), rs.getBoolean("can_text"),
                        rs.getBoolean("can_react"), rs.getBoolean("can_send_gifs")), array(tables.keySet()));
        UUID pro = main.get("pro");
        Set<UUID> proTables = members.stream().filter(m -> m.user().equals(pro)).map(Member::table)
                .collect(Collectors.toSet());
        members = members.stream().filter(m -> !bots.containsKey(m.user()) || botIds.contains(m.user())).toList();
        return new World(pro, mainIds, tables, members, proTables, botIds);
    }

    // ---------------------------------------------------------------- bets

    private record OpenBet(UUID id, UUID table, String mode, BigDecimal line, List<String> choices, Instant locksAt) {}

    private List<OpenBet> openBets(Collection<UUID> tables, Instant now) {
        if (tables.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT id, table_id, mode, config ->> 'line' AS line, choices::text AS choices, locks_at FROM bets
                WHERE table_id = ANY (?::uuid[]) AND status = 'ACTIVE' AND locks_at > ?
                """, (rs, row) -> new OpenBet(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getString("mode"), rs.getString("line") == null ? null : new BigDecimal(rs.getString("line")),
                        strings(rs.getString("choices")), rs.getTimestamp("locks_at").toInstant()),
                array(tables), Timestamp.from(now.plus(LOCK_MARGIN)));
    }

    private void planPicks(World world, Instant now) {
        List<OpenBet> open = openBets(world.tables.keySet(), now);
        if (open.isEmpty()) return;
        Map<UUID, Map<UUID, String>> picked = new HashMap<>();
        jdbc.query("SELECT bet_id, user_id, choice FROM picks WHERE bet_id = ANY (?::uuid[])", rs -> {
            picked.computeIfAbsent(rs.getObject("bet_id", UUID.class), id -> new HashMap<>())
                    .put(rs.getObject("user_id", UUID.class), rs.getString("choice"));
        }, array(open.stream().map(OpenBet::id).toList()));
        for (OpenBet bet : open) {
            Map<UUID, String> betPicks = picked.getOrDefault(bet.id(), Map.of());
            for (Member member : world.botsIn(bet.table(), bots)) {
                if (!member.canBet() || betPicks.containsKey(member.user())) continue;
                DemoUser bot = bots.get(member.user());
                if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("pick", bet.id(), bot.id())) continue;
                if (random.nextDouble() >= bot.profile().joinBetChance()) continue;
                Duration delay = BotMind.delay(random, bot.profile(), Duration.between(now, bet.locksAt().minus(LOCK_MARGIN)));
                plan(now.plus(delay), "pick", () -> {
                    Map<String, Integer> counts = new HashMap<>();
                    jdbc.query("SELECT choice, count(*) AS n FROM picks WHERE bet_id = ? GROUP BY choice",
                            rs -> { counts.put(rs.getString("choice"), rs.getInt("n")); }, bet.id());
                    String choice = BotMind.pick(random, bot.profile().pickStyle(), bet.mode(), bet.line(),
                            bet.choices(), counts);
                    return act(bot.id(), jwt -> picks.change(jwt, bet.id(), choice, key()).name());
                });
            }
        }
    }

    private void planProposals(World world, Instant now) {
        if (world.pro == null || world.proTables.isEmpty()) return;
        int open = openBets(world.proTables, now).size() + (int) queue.stream().filter(p -> p.what().equals("propose")).count();
        List<Member> proposers = world.members.stream()
                .filter(m -> world.proTables.contains(m.table()) && bots.containsKey(m.user()) && m.canBet()
                        && (m.host() || !world.tables.get(m.table()).hostsOnlyBets()))
                .toList();
        if (proposers.isEmpty()) return;
        // Everyone's own pace, while the pro's tables aren't already crowded.
        if (open < PRO_OPEN + 3) {
            for (Member member : proposers) {
                DemoUser bot = bots.get(member.user());
                if (BotMind.online(bot.id(), bot.profile(), now)
                        && BotMind.happens(random, bot.profile().proposalsPerHour(), tick)) {
                    planProposal(world, member, now);
                    open++;
                }
            }
        }
        // Topping up: someone (preferably online) proposes when the pro has fewer than five open bets to join.
        if (open < PRO_OPEN) {
            List<Member> online = proposers.stream()
                    .filter(m -> BotMind.online(m.user(), bots.get(m.user()).profile(), now)).toList();
            planProposal(world, DemoLines.any(random, online.isEmpty() ? proposers : online), now);
        }
    }

    private record Game(String id, String league, String status, Integer homeScore, Integer awayScore, Integer period) {}

    private void planProposal(World world, Member member, Instant now) {
        DemoUser bot = bots.get(member.user());
        Table table = world.tables.get(member.table());
        plan(now.plus(BotMind.delay(random, bot.profile(), Duration.ofSeconds(30))), "propose", () -> {
            List<Game> games = jdbc.query("""
                    SELECT id, league, status, home_score, away_score, period FROM games
                    WHERE (status = 'LIVE' OR (status = 'SCHEDULED' AND starts_at BETWEEN ? AND ?))
                      AND league = ANY (?::text[])
                    """, (rs, row) -> new Game(rs.getString("id"), rs.getString("league"), rs.getString("status"),
                            (Integer) rs.getObject("home_score"), (Integer) rs.getObject("away_score"),
                            (Integer) rs.getObject("period")),
                    Timestamp.from(now.plus(Duration.ofMinutes(15))), Timestamp.from(now.plus(Duration.ofHours(4))),
                    "{" + bot.profile().leagues().stream().map(Enum::name).collect(Collectors.joining(",")) + "}");
            long wager = BotMind.wager(random, bot.profile());
            boolean forFun = random.nextDouble() < 0.1;
            int window = 90 + random.nextInt(511);
            if (!games.isEmpty() && (!table.u2pickEnabled() || random.nextDouble() < 0.6)) {
                Game game = DemoLines.any(random, games);
                boolean total = random.nextBoolean();
                BigDecimal line = total ? totalLine(game) : spreadLine();
                var proposal = new SportsProposalService.Proposal(total ? "total_disaster" : "give_and_take",
                        game.id(), line, random.nextBoolean(), forFun ? 0 : wager, window, forFun);
                return act(bot.id(), jwt -> sportsBets.create(jwt, member.table(), proposal, key()).outcome().name());
            }
            if (!table.u2pickEnabled()) return "SKIPPED";
            DemoLines.Question question = DemoLines.any(random, DemoLines.QUESTIONS);
            var proposal = new U2PickProposalService.Proposal(
                    new U2PickDefinition(question.question(), question.choices()), json.createObjectNode(),
                    forFun ? 0 : wager, window, forFun,
                    random.nextDouble() < 0.7 ? U2PickSettler.Rule.HOSTS : U2PickSettler.Rule.ANYONE);
            return act(bot.id(), jwt -> u2picks.create(jwt, member.table(), proposal, key()).outcome().name());
        });
    }

    /** Totals in half points (no pushes): a typical full game before it starts, the pace so far once it's live. */
    private BigDecimal totalLine(Game game) {
        boolean nba = game.league().equals("NBA");
        int base = nba ? 224 : 44;
        if (game.status().equals("LIVE") && game.homeScore() != null && game.awayScore() != null) {
            int so = game.homeScore() + game.awayScore();
            int played = Math.max(1, Math.min(4, game.period() == null ? 1 : game.period()));
            base = Math.max(so + 1, Math.round(so * 4f / played));
        }
        return BigDecimal.valueOf(base + random.nextInt(nba ? 13 : 7) - (nba ? 6 : 3)).add(new BigDecimal("0.5"));
    }

    private BigDecimal spreadLine() {
        BigDecimal line = BigDecimal.valueOf(1 + random.nextInt(9)).add(new BigDecimal("0.5"));
        return random.nextBoolean() ? line : line.negate();
    }

    private record LockedU2Pick(UUID id, UUID table, UUID proposer, List<String> choices, String settler,
            Instant lockedAt, boolean proIn) {}

    private void planResults(World world, Instant now) {
        if (world.tables.isEmpty()) return;
        int proPending = world.pro == null ? 0 : jdbc.queryForObject("""
                SELECT count(*) FROM bets b JOIN picks p ON p.bet_id = b.id
                WHERE p.user_id = ? AND b.status = 'PENDING'
                """, Integer.class, world.pro);
        List<LockedU2Pick> locked = jdbc.query("""
                SELECT b.id, b.table_id, b.proposer_id, b.choices::text AS choices, b.settler_rule, b.locked_at,
                       EXISTS (SELECT 1 FROM picks p WHERE p.bet_id = b.id AND p.user_id = ?) AS pro_in
                FROM bets b WHERE b.table_id = ANY (?::uuid[]) AND b.status = 'PENDING' AND b.mode = 'u2pick'
                ORDER BY b.locked_at
                """, (rs, row) -> new LockedU2Pick(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getObject("proposer_id", UUID.class), strings(rs.getString("choices")),
                        rs.getString("settler_rule"), rs.getTimestamp("locked_at").toInstant(), rs.getBoolean("pro_in")),
                world.pro, array(world.tables.keySet()));
        for (LockedU2Pick bet : locked) {
            // Five to thirty minutes after lock, but bets the pro is in wait while he has 20 or fewer Pending.
            long minutes = 5 + Math.floorMod(bet.id().hashCode(), 26);
            if (now.isBefore(bet.lockedAt().plus(Duration.ofMinutes(minutes)))) continue;
            if (bet.proIn() && proPending <= PRO_PENDING) continue;
            Map<UUID, String> betPicks = new HashMap<>();
            jdbc.query("SELECT user_id, choice FROM picks WHERE bet_id = ?",
                    rs -> { betPicks.put(rs.getObject("user_id", UUID.class), rs.getString("choice")); }, bet.id());
            List<Member> eligible = world.botsIn(bet.table(), bots).stream().filter(m -> switch (bet.settler()) {
                case "hosts" -> m.host();
                case "me" -> m.user().equals(bet.proposer());
                case "non_participants" -> !betPicks.containsKey(m.user());
                default -> true;
            }).toList();
            if (eligible.isEmpty() || !decide("result", bet.id(), null)) continue;
            Member settler = DemoLines.any(random, eligible);
            List<String> pickedChoices = new ArrayList<>(betPicks.values());
            String winner = pickedChoices.isEmpty() ? DemoLines.any(random, bet.choices())
                    : DemoLines.any(random, pickedChoices);
            if (bet.proIn()) proPending--;
            plan(now, "result", () -> act(settler.user(), jwt -> results.resolve(jwt, bet.id(), winner, key()).name()));
        }
    }

    private void planPointsSettlement(World world, Instant now) {
        long hour = now.getEpochSecond() / 3600;
        for (Table table : world.tables.values()) {
            List<Member> hosts = world.botsIn(table.id(), bots).stream().filter(Member::host).toList();
            if (hosts.isEmpty()) continue;
            int waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM bets WHERE table_id = ? AND status IN ('SETTLED', 'WASHED')
                      AND NOT is_4fun AND points_settled_at IS NULL
                    """, Integer.class, table.id());
            if (waiting < 6 || !decide("points-" + hour, table.id(), null)) continue;
            UUID host = DemoLines.any(random, hosts).user();
            plan(now.plus(Duration.ofSeconds(random.nextInt(120))), "settle-points",
                    () -> act(host, jwt -> pointsSettlement.settle(jwt, table.id(), key()).outcome().name()));
        }
    }

    // ---------------------------------------------------------------- chat

    private void planChat(World world, Instant now) {
        for (Member member : world.members) {
            DemoUser bot = bots.get(member.user());
            if (bot == null || !member.canText() || !BotMind.online(bot.id(), bot.profile(), now)) continue;
            if (!BotMind.happens(random, bot.profile().messagesPerHour(), tick)) continue;
            boolean gif = member.canGif() && random.nextDouble() < bot.profile().gifChance();
            plan(now, gif ? "gif" : "message", () -> say(bot.id(), member.table(), gif
                    ? new ChatSendService.Submission("gif", DemoLines.any(random, gifs.catalog().gifs()).id())
                    : new ChatSendService.Submission("text", DemoLines.any(random, DemoLines.CHAT))));
        }
    }

    private String say(UUID bot, UUID table, ChatSendService.Submission submission) {
        return act(bot, jwt -> chat.send(jwt, table, key(), submission).outcome().name());
    }

    private void planReactions(World world, Instant now) {
        if (world.tables.isEmpty()) return;
        jdbc.query("""
                SELECT id, table_id, author_id FROM chat_messages
                WHERE table_id = ANY (?::uuid[]) AND kind IN ('text', 'gif', 'bet') AND author_id IS NOT NULL
                  AND created_at > ?
                """, rs -> {
                    UUID message = rs.getObject("id", UUID.class);
                    UUID author = rs.getObject("author_id", UUID.class);
                    for (Member member : world.botsIn(rs.getObject("table_id", UUID.class), bots)) {
                        DemoUser bot = bots.get(member.user());
                        if (member.user().equals(author) || !member.canReact()
                                || !BotMind.online(bot.id(), bot.profile(), now)
                                || !decide("react", message, bot.id())
                                || random.nextDouble() >= bot.profile().reactionChance()) {
                            continue;
                        }
                        String emoji = DemoLines.any(random, DemoLines.REACTIONS);
                        plan(now.plus(BotMind.delay(random, bot.profile(), null)), "react",
                                () -> act(bot.id(), jwt -> reactions.change(jwt, message, emoji, key()).name()));
                    }
                }, array(world.tables.keySet()), Timestamp.from(now.minus(Duration.ofMinutes(10))));
    }

    /** After a bet settles, its demo participants may gloat or grumble. */
    private void planTrashTalk(World world, Instant now) {
        if (world.tables.isEmpty()) return;
        jdbc.query("""
                SELECT b.id, b.table_id, b.winning_choice, p.user_id, p.choice FROM bets b
                JOIN picks p ON p.bet_id = b.id
                WHERE b.table_id = ANY (?::uuid[]) AND b.status = 'SETTLED' AND b.closed_at > ?
                """, rs -> {
                    UUID bet = rs.getObject("id", UUID.class);
                    UUID table = rs.getObject("table_id", UUID.class);
                    DemoUser bot = bots.get(rs.getObject("user_id", UUID.class));
                    if (bot == null || !decide("talk", bet, bot.id())
                            || random.nextDouble() >= bot.profile().trashTalkChance()) {
                        return;
                    }
                    boolean won = rs.getString("choice").equals(rs.getString("winning_choice"));
                    String line = DemoLines.any(random, won ? DemoLines.WIN : DemoLines.LOSS);
                    plan(now.plus(BotMind.delay(random, bot.profile(), null)), "trash-talk", () -> act(bot.id(), jwt -> {
                        var result = chat.send(jwt, table, key(), new ChatSendService.Submission("text", line));
                        if (result.message() != null) trashTalk.put(result.message().id(), bot.id());
                        return result.outcome().name();
                    }));
                }, array(world.tables.keySet()), Timestamp.from(now.minus(Duration.ofMinutes(3))));
    }

    /** Trash talk from another demo user can get them reported or blocked by table-mates who don't like it. */
    private void planComplaints(World world, Instant now) {
        if (trashTalk.isEmpty()) return;
        Map<UUID, UUID> messageTables = new HashMap<>();
        jdbc.query("SELECT id, table_id FROM chat_messages WHERE id = ANY (?::uuid[])",
                rs -> { messageTables.put(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class)); },
                array(trashTalk.keySet()));
        for (Map.Entry<UUID, UUID> talk : new ArrayList<>(trashTalk.entrySet())) {
            UUID message = talk.getKey();
            UUID author = talk.getValue();
            UUID table = messageTables.get(message);
            trashTalk.remove(message);
            if (table == null) continue;
            for (Member member : world.botsIn(table, bots)) {
                DemoUser bot = bots.get(member.user());
                if (member.user().equals(author)) continue;
                if (random.nextDouble() < bot.profile().reportChance()) {
                    plan(now.plus(BotMind.delay(random, bot.profile(), null)), "report", () -> act(bot.id(),
                            jwt -> reports.submit(jwt, key(),
                                    new ReportService.Submission(author, "harassment", "", table, message)).name()));
                }
                if (random.nextDouble() < bot.profile().blockChance()) {
                    plan(now.plus(BotMind.delay(random, bot.profile(), null)), "block", () -> act(bot.id(),
                            jwt -> social.mutate(jwt, author, key(), SocialMutationService.Operation.BLOCK).name()));
                }
            }
        }
    }

    // ---------------------------------------------------------------- social

    private void planSocial(World world, Instant now) {
        if (world.botIds.isEmpty()) return;
        // Follow-backs: only for follows made since the bots started, so the seeded world stays as seeded.
        jdbc.query("""
                SELECT f.follower_id, f.followee_id FROM follows f
                WHERE f.followee_id = ANY (?::uuid[]) AND f.created_at > ?
                  AND NOT EXISTS (SELECT 1 FROM follows r WHERE r.follower_id = f.followee_id AND r.followee_id = f.follower_id)
                """, rs -> {
                    UUID follower = rs.getObject("follower_id", UUID.class);
                    DemoUser bot = bots.get(rs.getObject("followee_id", UUID.class));
                    if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("follow-back", follower, bot.id())
                            || random.nextDouble() >= bot.profile().followBackChance()) {
                        return;
                    }
                    plan(now.plus(BotMind.delay(random, bot.profile(), null)), "follow-back", () -> act(bot.id(),
                            jwt -> social.mutate(jwt, follower, key(), SocialMutationService.Operation.FOLLOW).name()));
                }, array(world.botIds), Timestamp.from(since));
        // Invitations to demo users.
        jdbc.query("SELECT id, user_id FROM table_invites WHERE status = 'PENDING' AND user_id = ANY (?::uuid[])", rs -> {
            UUID invitation = rs.getObject("id", UUID.class);
            DemoUser bot = bots.get(rs.getObject("user_id", UUID.class));
            if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("invite", invitation, bot.id())) return;
            boolean accept = random.nextDouble() < bot.profile().inviteAcceptChance();
            plan(now.plus(BotMind.delay(random, bot.profile(), null)), accept ? "accept-invite" : "decline-invite",
                    () -> act(bot.id(), jwt -> invitations.respond(jwt, invitation, accept, key()).name()));
        }, array(world.botIds));
        // Following someone new now and then: mostly other demo users, sometimes the pro.
        List<UUID> others = new ArrayList<>(world.botIds);
        for (UUID id : world.botIds) {
            DemoUser bot = bots.get(id);
            if (!BotMind.online(id, bot.profile(), now) || !BotMind.happens(random, bot.profile().followsPerDay() / 24, tick)) {
                continue;
            }
            UUID target = world.pro != null && random.nextDouble() < 0.1 ? world.pro : DemoLines.any(random, others);
            if (target.equals(id)) continue;
            plan(now, "follow", () -> act(id, jwt -> social.mutate(jwt, target, key(), SocialMutationService.Operation.FOLLOW).name()));
        }
    }

    // ---------------------------------------------------------------- settle-ups

    private record Receipt(UUID id, UUID payer, UUID payee, String status, int revision, Instant createdAt,
            Instant updatedAt) {}

    private void planReceipts(World world, Instant now) {
        if (world.botIds.isEmpty()) return;
        List<Receipt> open = jdbc.query("""
                SELECT id, payer_id, payee_id, status, revision, created_at, updated_at FROM settlement_lines
                WHERE (payer_id = ANY (?::uuid[]) OR payee_id = ANY (?::uuid[]))
                  AND status IN ('unpaid', 'sent', 'forgive_asked')
                """, (rs, row) -> new Receipt(rs.getObject("id", UUID.class), rs.getObject("payer_id", UUID.class),
                        rs.getObject("payee_id", UUID.class), rs.getString("status"), rs.getInt("revision"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                array(world.botIds), array(world.botIds));
        for (Receipt receipt : open) {
            DemoUser payer = bots.get(receipt.payer());
            DemoUser payee = bots.get(receipt.payee());
            DemoUser actor;
            SettlementReceiptService.Action action;
            Duration wait;
            Random fixed = new Random(receipt.id().hashCode() * 31L + receipt.revision());
            if (receipt.status().equals("unpaid") && payer != null) {
                actor = payer;
                var style = payer.profile().settleStyle();
                action = style == DemoProfile.SettleStyle.DISPUTES && fixed.nextDouble() < 0.3
                        ? SettlementReceiptService.Action.ASK_FORGIVE : SettlementReceiptService.Action.SENT;
                wait = style == DemoProfile.SettleStyle.SLOW || style == DemoProfile.SettleStyle.DISPUTES
                        ? Duration.ofMinutes(60 + fixed.nextInt(300)) : Duration.ofMinutes(1 + fixed.nextInt(10));
            } else if (receipt.status().equals("unpaid") && payee != null
                    && payee.profile().settleStyle() == DemoProfile.SettleStyle.FORGIVES && fixed.nextBoolean()) {
                actor = payee;
                action = SettlementReceiptService.Action.FORGIVE;
                wait = Duration.ofMinutes(10 + fixed.nextInt(50));
            } else if (receipt.status().equals("sent") && payee != null) {
                actor = payee;
                action = payee.profile().settleStyle() == DemoProfile.SettleStyle.DISPUTES && fixed.nextDouble() < 0.4
                        ? SettlementReceiptService.Action.DISPUTE : SettlementReceiptService.Action.CONFIRM;
                wait = Duration.ofMinutes(1 + fixed.nextInt(15));
            } else if (receipt.status().equals("forgive_asked") && payee != null) {
                actor = payee;
                action = payee.profile().settleStyle() == DemoProfile.SettleStyle.FORGIVES || fixed.nextDouble() < 0.3
                        ? SettlementReceiptService.Action.FORGIVE : SettlementReceiptService.Action.DECLINE_FORGIVE;
                wait = Duration.ofMinutes(2 + fixed.nextInt(20));
            } else {
                continue;
            }
            if (now.isBefore(receipt.updatedAt().plus(wait))
                    || !decide("receipt-" + receipt.status() + "-" + receipt.revision(), receipt.id(), actor.id())) {
                continue;
            }
            UUID other = actor.id().equals(receipt.payer()) ? receipt.payee() : receipt.payer();
            plan(now, "receipt-" + action.name().toLowerCase(), () -> act(actor.id(), jwt -> {
                try {
                    return receipts.transition(jwt, receipt.id(), action, receipt.revision(), key()).outcome().name();
                } catch (SettlementReviewService.ReviewRequired required) {
                    // Like a player: look at the balance-change warning, then try again.
                    List<UUID> revision = jdbc.queryForList("""
                            SELECT revision FROM settlement_pair_reviews
                            WHERE user_id = ? AND other_user_id = ? AND reviewed_at IS NULL
                            """, UUID.class, actor.id(), other);
                    if (revision.isEmpty()) return "REVIEW_MISSING";
                    reviews.acknowledge(jwt, other, revision.getFirst(), key());
                    return receipts.transition(jwt, receipt.id(), action, receipt.revision(), key()).outcome().name();
                }
            }));
        }
    }

    // ---------------------------------------------------------------- plumbing

    private boolean decide(String what, UUID thing, UUID bot) {
        return decided.add(what + ":" + thing + ":" + bot);
    }

    private void plan(Instant at, String what, java.util.function.Supplier<String> action) {
        queue.add(new Planned(at, what, () -> {
            String outcome = action.get();
            done.merge(what + (outcome == null ? "" : " " + outcome.toLowerCase()), 1, Integer::sum);
        }));
    }

    private void runDue(Instant now) {
        int ran = 0;
        // A cap per tick keeps one tick short even after a pause; the rest run next time.
        while (!queue.isEmpty() && !queue.peek().at().isAfter(now) && ran++ < 50) {
            Planned planned = queue.poll();
            try {
                planned.action().run();
            } catch (RuntimeException exception) {
                done.merge(planned.what() + " failed", 1, Integer::sum);
                log.debug("Demo bot {} failed: {}", planned.what(), exception.toString());
            }
        }
    }

    /** Runs an action as the bot with a live session; an UNAUTHORIZED outcome drops the session for next time. */
    private String act(UUID bot, Function<Jwt, String> action) {
        Jwt jwt = sessions.get(bot);
        if (jwt == null || jwt.getExpiresAt() == null
                || jwt.getExpiresAt().isBefore(clock.instant().plus(Duration.ofMinutes(10)))) {
            jwt = signIn.session(bot);
            if (jwt == null) return "NO_SESSION";
            sessions.put(bot, jwt);
        }
        String outcome = action.apply(jwt);
        if ("UNAUTHORIZED".equals(outcome)) sessions.remove(bot);
        return outcome;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    private List<String> strings(String jsonArray) {
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unreadable choices", exception);
        }
    }
}

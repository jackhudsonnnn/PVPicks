package com.p2picks.app.demo;

import static com.p2picks.app.demo.DemoBots.PRO_OPEN;
import static com.p2picks.app.demo.DemoBots.PRO_PENDING;
import static com.p2picks.app.demo.DemoBots.CROWD_OPEN_MIN;
import static com.p2picks.app.demo.DemoBots.CROWD_OPEN_MAX;
import static com.p2picks.app.demo.DemoBots.LOCK_MARGIN;

import com.p2picks.app.bets.BetPickService;
import com.p2picks.app.bets.HoldemService;
import com.p2picks.app.bets.JackblackService;
import com.p2picks.app.bets.SportsProposalService;
import com.p2picks.app.bets.U2PickDefinition;
import com.p2picks.app.bets.U2PickProposalService;
import com.p2picks.app.bets.U2PickResolutionService;
import com.p2picks.app.bets.U2PickSettler;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.sports.PlayerStatsLookup;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.p2picks.app.demo.BotWorld.Member;
import com.p2picks.app.demo.BotWorld.OpenBet;
import com.p2picks.app.demo.BotWorld.Table;
import com.p2picks.app.demo.BotWorld.World;

/** Bots betting: picks on open bets, proposals (keeping the pro's tables and Mosh Pit busy), and calling U2Pick results. */
final class BetBots extends BotPlanner {
    private record Game(String id, String league, String status, Integer homeScore, Integer awayScore, Integer period) {}

    private static final List<String> SPORTS_MODES = List.of("total_disaster", "give_and_take", "either_or",
            "prop_hunt", "king_of_the_hill", "scorcerer");

    private record LockedU2Pick(UUID id, UUID table, UUID proposer, List<String> choices, String settler,
            Instant lockedAt, boolean proIn) {}

    private final BetPickService picks;
    private final U2PickProposalService u2picks;
    private final SportsProposalService sportsBets;
    private final U2PickResolutionService results;
    private final PlayerStatsLookup playerStats;
    private final JackblackService jackblack;
    private final HoldemService holdem;

    BetBots(BotKit kit, BetPickService picks, U2PickProposalService u2picks, SportsProposalService sportsBets, U2PickResolutionService results, PlayerStatsLookup playerStats, JackblackService jackblack, HoldemService holdem) {
        super(kit);
        this.picks = picks;
        this.u2picks = u2picks;
        this.sportsBets = sportsBets;
        this.results = results;
        this.playerStats = playerStats;
        this.jackblack = jackblack;
        this.holdem = holdem;
    }

    void planPicks(World world, Instant now) {
        List<OpenBet> open = openBets(world.tables().keySet(), now);
        if (open.isEmpty()) return;
        Map<UUID, Map<UUID, String>> picked = new HashMap<>();
        jdbc.query("SELECT bet_id, user_id, choice FROM picks WHERE bet_id = ANY (?::uuid[])", rs -> {
            picked.computeIfAbsent(rs.getObject("bet_id", UUID.class), id -> new HashMap<>())
                    .put(rs.getObject("user_id", UUID.class), rs.getString("choice"));
        }, array(open.stream().map(OpenBet::id).toList()));
        for (OpenBet bet : open) {
            // The card games are played at the table (planJackblack, planHoldem), not picked.
            if (bet.mode().equals(JackblackService.MODE) || bet.mode().equals(HoldemService.MODE)) continue;
            Map<UUID, String> betPicks = picked.getOrDefault(bet.id(), Map.of());
            for (Member member : world.botsIn(bet.table(), bots)) {
                if (!member.canBet() || betPicks.containsKey(member.user())) continue;
                DemoUser bot = bots.get(member.user());
                if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("pick", bet.id(), bot.id())) continue;
                if (random().nextDouble() >= bot.profile().joinBetChance()) continue;
                Duration delay = BotMind.delay(random(), bot.profile(), Duration.between(now, bet.locksAt().minus(LOCK_MARGIN)));
                plan(now.plus(delay), "pick", () -> {
                    Map<String, Integer> counts = new HashMap<>();
                    jdbc.query("SELECT choice, count(*) AS n FROM picks WHERE bet_id = ? GROUP BY choice",
                            rs -> { counts.put(rs.getString("choice"), rs.getInt("n")); }, bet.id());
                    String choice = BotMind.pick(random(), bot.profile().pickStyle(), bet.mode(), bet.line(),
                            bet.choices(), counts);
                    return act(bot.id(), jwt -> picks.change(jwt, bet.id(), choice, key()).name());
                });
            }
        }
    }

    void planProposals(World world, Instant now) {
        if (world.pro() == null || world.proTables().isEmpty()) return;
        // The pro's regular tables share one budget; Mosh Pit, if he still has it, has its own.
        Set<UUID> regular = new HashSet<>(world.proTables());
        regular.remove(DemoWorld.CROWD_TABLE);
        planProposals(world, regular, PRO_OPEN, PRO_OPEN + 3, "propose", now);
        if (world.proTables().contains(DemoWorld.CROWD_TABLE)) {
            planProposals(world, Set.of(DemoWorld.CROWD_TABLE), CROWD_OPEN_MIN, CROWD_OPEN_MAX, "propose-crowd", now);
        }
    }

    /**
     * Proposals in {@code tables}: bots propose at their own pace while fewer than {@code max} bets are open there,
     * and someone tops up whenever fewer than {@code min} are.
     */
    void planProposals(World world, Set<UUID> tables, int min, int max, String what, Instant now) {
        if (tables.isEmpty()) return;
        int open = openBets(tables, now).size() + (int) kit.queued(what);
        List<Member> proposers = world.members().stream()
                .filter(m -> tables.contains(m.table()) && bots.containsKey(m.user()) && m.canBet()
                        && (m.host() || !world.tables().get(m.table()).hostsOnlyBets()))
                .toList();
        if (proposers.isEmpty()) return;
        // Everyone's own pace, while the tables aren't already crowded.
        if (open < max) {
            for (Member member : proposers) {
                DemoUser bot = bots.get(member.user());
                if (BotMind.online(bot.id(), bot.profile(), now)
                        && BotMind.happens(random(), bot.profile().proposalsPerHour(), tick)) {
                    planProposal(world, member, what, now);
                    open++;
                }
            }
        }
        // Topping up: someone (preferably online) proposes when the pro has too few open bets to join there.
        if (open < min) {
            List<Member> online = proposers.stream()
                    .filter(m -> BotMind.online(m.user(), bots.get(m.user()).profile(), now)).toList();
            planProposal(world, DemoLines.any(random(), online.isEmpty() ? proposers : online), what, now);
        }
    }

    void planProposal(World world, Member member, String what, Instant now) {
        DemoUser bot = bots.get(member.user());
        Table table = world.tables().get(member.table());
        plan(now.plus(BotMind.delay(random(), bot.profile(), Duration.ofSeconds(30))), what, () -> {
            List<Game> games = jdbc.query("""
                    SELECT id, league, status, home_score, away_score, period FROM games
                    WHERE (status = 'LIVE' OR (status = 'SCHEDULED' AND starts_at BETWEEN ? AND ?))
                      AND league = ANY (?::text[])
                    """, (rs, row) -> new Game(rs.getString("id"), rs.getString("league"), rs.getString("status"),
                            (Integer) rs.getObject("home_score"), (Integer) rs.getObject("away_score"),
                            (Integer) rs.getObject("period")),
                    Timestamp.from(now.plus(Duration.ofMinutes(15))), Timestamp.from(now.plus(Duration.ofHours(4))),
                    "{" + bot.profile().leagues().stream().map(Enum::name).collect(Collectors.joining(",")) + "}");
            long wager = BotMind.wager(random(), bot.profile());
            boolean forFun = random().nextDouble() < 0.1;
            // Now and then a bot proposes Jackblack, where the table lets it.
            boolean canDeal = table.jackblackAccess().equals("everyone")
                    || (table.jackblackAccess().equals("hosts") && member.host());
            if (canDeal && random().nextDouble() < 0.15) {
                var rules = JackblackBot.rules(random());
                long ante = forFun ? 0 : Math.min(wager, 200);
                return act(bot.id(), jwt -> jackblack.propose(jwt, member.table(), rules, ante, 60, forFun, key())
                        .outcome().name());
            }
            // Now and then a bot proposes Hold'em, where the table lets it.
            boolean canHoldem = table.holdemAccess().equals("everyone")
                    || (table.holdemAccess().equals("hosts") && member.host());
            if (canHoldem && random().nextDouble() < 0.08) {
                long bigBlind = forFun ? 0 : Math.max(10, Math.min(wager, 100));
                return act(bot.id(), jwt -> holdem.propose(jwt, member.table(), HoldemBot.rules(random()), bigBlind,
                        90, forFun, key()).outcome().name());
            }
            int window = 90 + random().nextInt(511);
            // Only the modes this bot likes (all when its profile names none) that the table allows.
            Set<String> liked = bot.profile().modes();
            List<String> sportsModes = SPORTS_MODES.stream()
                    .filter(mode -> liked.isEmpty() || liked.contains(mode)).toList();
            boolean u2pick = table.u2pickEnabled() && (liked.isEmpty() || liked.contains("u2pick"));
            if (!games.isEmpty() && !sportsModes.isEmpty() && (!u2pick || random().nextDouble() < 0.6)) {
                Game game = DemoLines.any(random(), games);
                SportsProposalService.Proposal proposal = sportsProposal(game, DemoLines.any(random(), sportsModes),
                        forFun ? 0 : wager, window, forFun);
                if (proposal == null) return "SKIPPED";
                return act(bot.id(), jwt -> sportsBets.create(jwt, member.table(), proposal, key()).outcome().name());
            }
            if (!u2pick) return "SKIPPED";
            DemoLines.Question question = DemoLines.any(random(), DemoLines.QUESTIONS);
            // In Mosh Pit the pro is the only host, so a hosts-only U2Pick would wait on him; anyone calls them there.
            boolean hostsCall = !member.table().equals(DemoWorld.CROWD_TABLE) && random().nextDouble() < 0.7;
            var proposal = new U2PickProposalService.Proposal(
                    new U2PickDefinition(question.question(), question.choices()), json.createObjectNode(),
                    forFun ? 0 : wager, window, forFun,
                    hostsCall ? U2PickSettler.Rule.HOSTS : U2PickSettler.Rule.ANYONE);
            return act(bot.id(), jwt -> u2picks.create(jwt, member.table(), proposal, key()).outcome().name());
        });
    }

    /**
     * A sports proposal in {@code mode} on {@code game}, with sensible terms: lines near the pace, player lines and
     * targets a little above the players' current numbers. Null when the game can't take that mode (Scorcerer off
     * NFL or before kickoff, a player mode before the game has players).
     */
    SportsProposalService.Proposal sportsProposal(Game game, String mode, long wager, int window,
            boolean forFun) {
        boolean overtime = random().nextBoolean();
        return switch (mode) {
            case "total_disaster", "give_and_take" -> new SportsProposalService.Proposal(mode, game.id(),
                    mode.equals("total_disaster") ? totalLine(game) : spreadLine(), overtime, wager, window, forFun);
            case "scorcerer" -> game.league().equals("NFL") && game.status().equals("LIVE")
                    ? new SportsProposalService.Proposal(mode, game.id(), null, true, wager, window, forFun, null,
                            List.of())
                    : null;
            default -> {
                List<PlayerStatsLookup.GamePlayer> roster = playerStats.players(game.id()).stream()
                        .filter(PlayerStatsLookup.GamePlayer::played).toList();
                if (roster.size() < 2) yield null;
                var statKeys = com.p2picks.app.sports.StatLine.STATS
                        .get(com.p2picks.app.sports.League.valueOf(game.league())).stream()
                        .map(com.p2picks.app.sports.StatLine.Stat::key).toList();
                String stat = DemoLines.any(random(), statKeys);
                List<PlayerStatsLookup.GamePlayer> shuffled = new ArrayList<>(roster);
                java.util.Collections.shuffle(shuffled, random());
                PlayerStatsLookup.GamePlayer first = shuffled.get(0);
                PlayerStatsLookup.GamePlayer second = shuffled.get(1);
                int top = Math.max(first.stat(stat), second.stat(stat));
                yield switch (mode) {
                    case "either_or" -> new SportsProposalService.Proposal(mode, game.id(), null, overtime, wager,
                            window, forFun, stat, List.of(first.id(), second.id()));
                    case "prop_hunt" -> new SportsProposalService.Proposal(mode, game.id(),
                            BigDecimal.valueOf(first.stat(stat) + 1 + random().nextInt(6)).add(new BigDecimal("0.5")),
                            overtime, wager, window, forFun, stat, List.of(first.id()));
                    default -> new SportsProposalService.Proposal(mode, game.id(),
                            BigDecimal.valueOf(top + 2 + random().nextInt(8)), overtime, wager, window, forFun, stat,
                            List.of(first.id(), second.id()));
                };
            }
        };
    }

    /** Totals in half points (no pushes): a typical full game before it starts, the pace so far once it's live. */
    BigDecimal totalLine(Game game) {
        boolean nba = game.league().equals("NBA");
        int base = nba ? 224 : 44;
        if (game.status().equals("LIVE") && game.homeScore() != null && game.awayScore() != null) {
            int so = game.homeScore() + game.awayScore();
            int played = Math.max(1, Math.min(4, game.period() == null ? 1 : game.period()));
            base = Math.max(so + 1, Math.round(so * 4f / played));
        }
        return BigDecimal.valueOf(base + random().nextInt(nba ? 13 : 7) - (nba ? 6 : 3)).add(new BigDecimal("0.5"));
    }

    BigDecimal spreadLine() {
        BigDecimal line = BigDecimal.valueOf(1 + random().nextInt(9)).add(new BigDecimal("0.5"));
        return random().nextBoolean() ? line : line.negate();
    }

    void planResults(World world, Instant now) {
        if (world.tables().isEmpty()) return;
        int proPending = world.pro() == null ? 0 : jdbc.queryForObject("""
                SELECT count(*) FROM bets b JOIN picks p ON p.bet_id = b.id
                WHERE p.user_id = ? AND b.status = 'PENDING'
                """, Integer.class, world.pro());
        List<LockedU2Pick> locked = jdbc.query("""
                SELECT b.id, b.table_id, b.proposer_id, b.choices::text AS choices, b.settler_rule, b.locked_at,
                       EXISTS (SELECT 1 FROM picks p WHERE p.bet_id = b.id AND p.user_id = ?) AS pro_in
                FROM bets b WHERE b.table_id = ANY (?::uuid[]) AND b.status = 'PENDING' AND b.mode = 'u2pick'
                ORDER BY b.locked_at
                """, (rs, row) -> new LockedU2Pick(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getObject("proposer_id", UUID.class), strings(rs.getString("choices")),
                        rs.getString("settler_rule"), rs.getTimestamp("locked_at").toInstant(), rs.getBoolean("pro_in")),
                world.pro(), array(world.tables().keySet()));
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
            Member settler = DemoLines.any(random(), eligible);
            List<String> pickedChoices = new ArrayList<>(betPicks.values());
            String winner = pickedChoices.isEmpty() ? DemoLines.any(random(), bet.choices())
                    : DemoLines.any(random(), pickedChoices);
            if (bet.proIn()) proPending--;
            plan(now, "result", () -> act(settler.user(), jwt -> results.resolve(jwt, bet.id(), winner, key()).name()));
        }
    }
}

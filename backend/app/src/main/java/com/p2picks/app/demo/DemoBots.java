package com.p2picks.app.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.bets.BetPickService;
import com.p2picks.app.bets.HoldemService;
import com.p2picks.app.bets.JackblackService;
import com.p2picks.app.bets.SportsProposalService;
import com.p2picks.app.bets.U2PickProposalService;
import com.p2picks.app.bets.U2PickResolutionService;
import com.p2picks.app.chat.ChatSendService;
import com.p2picks.app.chat.GifCatalog;
import com.p2picks.app.chat.ReactionService;
import com.p2picks.app.demo.BotWorld.Member;
import com.p2picks.app.demo.BotWorld.Table;
import com.p2picks.app.demo.BotWorld.World;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.moderation.ReportService;
import com.p2picks.app.security.PasswordlessSignIn;
import com.p2picks.app.settlement.SettleService;
import com.p2picks.app.settlement.SettlementReviewService;
import com.p2picks.app.settlement.TablePointsSettlementService;
import com.p2picks.app.social.InvitationResponseService;
import com.p2picks.app.social.SocialMutationService;
import com.p2picks.app.sports.PlayerStatsLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.stereotype.Component;

/**
 * The demo users, acting. Every few seconds the loop looks at the tables that include a main account (newbie,
 * intermediate, pro) and lets online demo users act there, per their {@link DemoProfile}, through the same services
 * real requests use: each bot has a real session and every rule applies to it. Decisions are made once per thing
 * (a bet, a follow, an invitation) and carried out after the bot's reaction delay. In-memory: a backend restart or
 * a reset starts the bots over. See docs/site/demo-data.md.
 *
 * <p>Each tick reads the world, then each planner plans its steps: {@link BetBots}, {@link CardGameBots},
 * {@link SocialBots} and {@link SettlingBots}, all on one scheduler ({@link BotKit}).
 */
@Component
@ConditionalOnExpression("${pvpicks.demo.enabled:false} and ${pvpicks.demo.bots.enabled:true}")
public class DemoBots {
    private static final Logger log = LoggerFactory.getLogger(DemoBots.class);
    private static final List<String> MAIN_PLAYERS = List.of("newbie", "intermediate", "pro");
    /** The pro is kept near this many open (Active) bets in his tables, and this many Pending bets of his own. */
    static final int PRO_OPEN = 5;
    static final int PRO_PENDING = 20;
    /** Mosh Pit has its own budget: the crowd keeps it between these many open bets, apart from the pro's five. */
    static final int CROWD_OPEN_MIN = 6;
    static final int CROWD_OPEN_MAX = 12;
    /** At most this many trash-talk lines per settled bet, so a 50-person bet doesn't flood the chat. */
    static final int TRASH_TALK_PER_BET = 3;
    /** Bots don't act on a bet this close to its lock (the action wouldn't land in time). */
    static final Duration LOCK_MARGIN = Duration.ofSeconds(3);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<UUID, DemoUser> bots = DemoRoster.users().stream()
            .collect(Collectors.toMap(DemoUser::id, Function.identity()));
    private final BotKit kit;
    private final BetBots betting;
    private final CardGameBots cards;
    private final SocialBots social;
    private final SettlingBots settling;
    private Instant lastTick;
    private volatile boolean paused;

    DemoBots(JdbcTemplate jdbc, ObjectMapper json, Clock clock,
            @Value("${pvpicks.demo.bots.tick:PT5S}") Duration tick, PasswordlessSignIn signIn, BetPickService picks,
            U2PickProposalService u2picks, SportsProposalService sportsBets, U2PickResolutionService results,
            SocialMutationService socialMutations, InvitationResponseService invitations, ChatSendService chat,
            ReactionService reactions, GifCatalog gifs, ReportService reports, SettleService settles,
            SettlementReviewService reviews, TablePointsSettlementService pointsSettlement,
            PlayerStatsLookup playerStats, JackblackService jackblack, HoldemService holdem) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.kit = new BotKit(jdbc, json, clock, tick, signIn, bots);
        this.betting = new BetBots(kit, picks, u2picks, sportsBets, results, playerStats, jackblack, holdem);
        this.cards = new CardGameBots(kit, jackblack, holdem);
        this.social = new SocialBots(kit, socialMutations, invitations, chat, reactions, gifs, reports);
        this.settling = new SettlingBots(kit, settles, reviews, pointsSettlement);
    }

    /** What the bots have done since they started (or the last reset), for the Dev page. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("since", kit.since().toString());
        status.put("lastTick", lastTick == null ? null : lastTick.toString());
        status.put("queued", kit.queued());
        status.put("done", kit.done());
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
        kit.reseed(seed);
    }

    /** Drops sessions, plans and decisions; called after a reset, when none of them mean anything. */
    synchronized void forget() {
        kit.forget();
        cards.forget();
        social.forget();
    }

    @Scheduled(fixedDelayString = "${pvpicks.demo.bots.tick:PT5S}",
            initialDelayString = "${pvpicks.demo.bots.initial-delay:PT20S}")
    public synchronized void tick() {
        if (paused) return;
        Instant now = clock.instant();
        lastTick = now;
        try {
            kit.runDue(now);
            World world = world(now);
            if (world.members().isEmpty() && world.botIds().isEmpty()) return;
            // The steps keep their order, so a reseeded test's decisions repeat.
            betting.planPicks(world, now);
            cards.planJackblack(world, now);
            cards.planHoldem(world, now);
            betting.planProposals(world, now);
            betting.planResults(world, now);
            social.planChat(world, now);
            social.planReactions(world, now);
            social.planTrashTalk(world, now);
            social.planComplaints(world, now);
            social.planSocial(world, now);
            settling.planReceipts(world, now);
            settling.planPointsSettlement(world, now);
        } catch (RuntimeException exception) {
            log.warn("Demo bots tick failed", exception);
        }
        if (kit.decided.size() > 200_000) kit.decided.clear();
        social.trim();
    }

    private World world(Instant now) {
        Map<String, UUID> main = new HashMap<>();
        jdbc.query("SELECT id, username::text AS name FROM users WHERE username::text = ANY (?::text[]) AND status = 'ACTIVE'",
                rs -> { main.put(rs.getString("name"), rs.getObject("id", UUID.class)); },
                "{" + String.join(",", MAIN_PLAYERS) + "}");
        Set<UUID> mainIds = new HashSet<>(main.values());
        Set<UUID> botIds = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users WHERE id = ANY (?::uuid[]) AND status = 'ACTIVE'", UUID.class, BotKit.array(bots.keySet())));
        Map<UUID, Table> tables = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DISTINCT t.id, t.hosts_only_bets, t.u2pick_enabled, t.jackblack_access, t.holdem_access
                FROM tables t
                JOIN table_members m ON m.table_id = t.id
                WHERE t.archived_at IS NULL AND m.user_id = ANY (?::uuid[])
                """, rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    tables.put(id, new Table(id, rs.getBoolean("hosts_only_bets"), rs.getBoolean("u2pick_enabled"),
                            rs.getString("jackblack_access"), rs.getString("holdem_access")));
                }, BotKit.array(mainIds));
        List<Member> members = tables.isEmpty() ? List.of() : jdbc.query("""
                SELECT table_id, user_id, role, can_bet, can_text, can_react, can_send_gifs FROM table_members
                WHERE table_id = ANY (?::uuid[])
                """, (rs, row) -> new Member(rs.getObject("table_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("role").equals("HOST"), rs.getBoolean("can_bet"), rs.getBoolean("can_text"),
                        rs.getBoolean("can_react"), rs.getBoolean("can_send_gifs")), BotKit.array(tables.keySet()));
        UUID pro = main.get("pro");
        Set<UUID> proTables = members.stream().filter(m -> m.user().equals(pro)).map(Member::table)
                .collect(Collectors.toSet());
        members = members.stream().filter(m -> !bots.containsKey(m.user()) || botIds.contains(m.user())).toList();
        return new World(pro, mainIds, tables, members, proTables, botIds);
    }
}

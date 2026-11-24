package com.p2picks.app.demo;

import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.settlement.SettleRules;
import com.p2picks.app.settlement.SettleService;
import com.p2picks.app.settlement.SettlementReviewService;
import com.p2picks.app.settlement.TablePointsSettlementService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import com.p2picks.app.demo.BotWorld.Member;
import com.p2picks.app.demo.BotWorld.Table;
import com.p2picks.app.demo.BotWorld.World;

/** Bots settling up: points settlements by hosts, and settles, reviews and rejections between people. */
final class SettlingBots extends BotPlanner {
    private record Receipt(UUID id, UUID payer, UUID payee, long amount, Instant createdAt) {}

    private final SettleService settles;
    private final SettlementReviewService reviews;
    private final TablePointsSettlementService pointsSettlement;

    SettlingBots(BotKit kit, SettleService settles, SettlementReviewService reviews, TablePointsSettlementService pointsSettlement) {
        super(kit);
        this.settles = settles;
        this.reviews = reviews;
        this.pointsSettlement = pointsSettlement;
    }

    /**
     * Settle-ups, per bot and person, by the bot's `settleStyle`: whoever is down on their open receipts settles them
     * (promptly, or slowly); a lenient bot that's up sometimes marks what's owed to it as settled; an even pair is
     * marked settled; and a disputing bot sometimes rejects a settle before its window closes.
     */
    void planReceipts(World world, Instant now) {
        if (world.botIds().isEmpty()) return;
        Map<List<UUID>, List<Receipt>> pairs = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, payer_id, payee_id, amount_cents, created_at FROM settlement_lines
                WHERE (payer_id = ANY (?::uuid[]) OR payee_id = ANY (?::uuid[])) AND status = 'unpaid'
                ORDER BY id
                """, rs -> {
                    Receipt receipt = new Receipt(rs.getObject("id", UUID.class), rs.getObject("payer_id", UUID.class),
                            rs.getObject("payee_id", UUID.class), rs.getLong("amount_cents"),
                            rs.getTimestamp("created_at").toInstant());
                    for (UUID side : List.of(receipt.payer(), receipt.payee())) {
                        if (!bots.containsKey(side)) continue;
                        UUID other = side.equals(receipt.payer()) ? receipt.payee() : receipt.payer();
                        pairs.computeIfAbsent(List.of(side, other), ignored -> new ArrayList<>()).add(receipt);
                    }
                }, array(world.botIds()), array(world.botIds()));
        pairs.forEach((pair, receipts) -> {
            DemoUser bot = bots.get(pair.get(0));
            UUID other = pair.get(1);
            long net = receipts.stream().mapToLong(r -> r.payee().equals(bot.id()) ? r.amount() : -r.amount()).sum();
            String identity = receipts.stream().map(r -> r.id().toString()).collect(Collectors.joining(","));
            Random fixed = new Random(identity.hashCode() * 31L + bot.id().hashCode());
            Instant newest = receipts.stream().map(Receipt::createdAt).max(Instant::compareTo).orElse(now);
            var style = bot.profile().settleStyle();
            List<UUID> chosen;
            String kind;
            Duration wait;
            if (net < 0) {
                chosen = receipts.stream().map(Receipt::id).toList();
                kind = "sent";
                wait = style == DemoProfile.SettleStyle.SLOW || style == DemoProfile.SettleStyle.REJECTS
                        ? Duration.ofMinutes(60 + fixed.nextInt(300)) : Duration.ofMinutes(1 + fixed.nextInt(10));
            } else if (net == 0) {
                chosen = receipts.stream().map(Receipt::id).toList();
                kind = "received";
                wait = Duration.ofMinutes(5 + fixed.nextInt(30));
            } else if (style == DemoProfile.SettleStyle.LENIENT && fixed.nextBoolean()) {
                chosen = receipts.stream().filter(r -> r.payee().equals(bot.id())).map(Receipt::id).toList();
                kind = "received";
                wait = Duration.ofMinutes(10 + fixed.nextInt(50));
            } else {
                return;
            }
            if (now.isBefore(newest.plus(wait)) || !decide("settle-" + identity, bot.id(), other)) return;
            SettleRules.Kind settleKind = SettleRules.Kind.of(kind);
            plan(now, "settle-" + kind, () -> act(bot.id(), jwt -> reviewing(jwt, bot.id(), other,
                    () -> settles.create(jwt, other, settleKind, chosen, key()).outcome().name())));
        });
        // Rejections: a disputing bot sometimes rejects a settle to it, a few minutes in.
        jdbc.query("""
                SELECT id, other_id, created_at FROM settles
                WHERE other_id = ANY (?::uuid[]) AND kind = 'sent' AND rejected_at IS NULL
                  AND reject_by > clock_timestamp()
                """, rs -> {
                    UUID settle = rs.getObject("id", UUID.class);
                    DemoUser receiver = bots.get(rs.getObject("other_id", UUID.class));
                    Random fixed = new Random(settle.hashCode());
                    if (receiver == null || receiver.profile().settleStyle() != DemoProfile.SettleStyle.REJECTS
                            || fixed.nextDouble() >= 0.4
                            || now.isBefore(rs.getTimestamp("created_at").toInstant()
                                    .plus(Duration.ofMinutes(2 + fixed.nextInt(20))))
                            || !decide("reject", settle, receiver.id())) {
                        return;
                    }
                    plan(now, "settle-rejected",
                            () -> act(receiver.id(), jwt -> settles.reject(jwt, settle, key()).outcome().name()));
                }, array(world.botIds()));
    }

    /** Like a player: if a balance change needs reviewing first, look at it, then try again. */
    String reviewing(Jwt jwt, UUID bot, UUID other, java.util.function.Supplier<String> action) {
        try {
            return action.get();
        } catch (SettlementReviewService.ReviewRequired required) {
            List<UUID> revision = jdbc.queryForList("""
                    SELECT revision FROM settlement_pair_reviews
                    WHERE user_id = ? AND other_user_id = ? AND reviewed_at IS NULL
                    """, UUID.class, bot, other);
            if (revision.isEmpty()) return "REVIEW_MISSING";
            reviews.acknowledge(jwt, other, revision.getFirst(), key());
            return action.get();
        }
    }

    void planPointsSettlement(World world, Instant now) {
        long hour = now.getEpochSecond() / 3600;
        for (Table table : world.tables().values()) {
            List<Member> hosts = world.botsIn(table.id(), bots).stream().filter(Member::host).toList();
            if (hosts.isEmpty()) continue;
            int waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM bets WHERE table_id = ? AND status IN ('SETTLED', 'WASHED')
                      AND NOT is_4fun AND points_settled_at IS NULL
                    """, Integer.class, table.id());
            if (waiting < 6 || !decide("points-" + hour, table.id(), null)) continue;
            UUID host = DemoLines.any(random(), hosts).user();
            plan(now.plus(Duration.ofSeconds(random().nextInt(120))), "settle-points",
                    () -> act(host, jwt -> pointsSettlement.settle(jwt, table.id(), key()).outcome().name()));
        }
    }
}

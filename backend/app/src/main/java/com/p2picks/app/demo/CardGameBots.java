package com.p2picks.app.demo;

import static com.p2picks.app.demo.DemoBots.LOCK_MARGIN;

import com.p2picks.app.bets.HoldemService;
import com.p2picks.app.bets.JackblackService;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.p2picks.app.demo.BotWorld.Member;
import com.p2picks.app.demo.BotWorld.OpenBet;
import com.p2picks.app.demo.BotWorld.World;

/** Bots at the card tables: joining Jackblack and Hold'em games and playing them (moves, the dealer's button and bets). */
final class CardGameBots extends BotPlanner {
    private final JackblackService jackblack;
    private final HoldemService holdem;
    /** Jackblack games a bot dealer has a tap queued for. */
    private final Set<UUID> dealing = new HashSet<>();

    CardGameBots(BotKit kit, JackblackService jackblack, HoldemService holdem) {
        super(kit);
        this.jackblack = jackblack;
        this.holdem = holdem;
    }

    @Override
    void forget() {
        dealing.clear();
    }

    /**
     * Jackblack: bots join open games at their tables (like joining a bet), then play their own steps with
     * {@link JackblackBot}'s strategy a second or two after the cards come out, and tap the dealer's button when it's
     * their round to deal.
     */
    void planJackblack(World world, Instant now) {
        for (OpenBet bet : openBets(world.tables().keySet(), now)) {
            if (!bet.mode().equals(JackblackService.MODE)) continue;
            for (Member member : world.botsIn(bet.table(), bots)) {
                if (!member.canBet()) continue;
                DemoUser bot = bots.get(member.user());
                if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("jb-join", bet.id(), bot.id())) continue;
                if (random().nextDouble() >= bot.profile().joinBetChance()) continue;
                Duration delay = BotMind.delay(random(), bot.profile(),
                        Duration.between(now, bet.locksAt().minus(LOCK_MARGIN)));
                plan(now.plus(delay), "jackblack-join",
                        () -> act(bot.id(), jwt -> jackblack.join(jwt, bet.id(), key()).name()));
            }
        }
        if (world.botIds().isEmpty()) return;
        jdbc.query("""
                SELECT p.bet_id, p.user_id FROM card_players p JOIN bets b ON b.id = p.bet_id
                WHERE b.mode = 'jackblack' AND b.status = 'PENDING' AND p.user_id = ANY (?::uuid[])
                """, rs -> {
                    UUID betId = rs.getObject("bet_id", UUID.class);
                    UUID botId = rs.getObject("user_id", UUID.class);
                    // Dealing: one tap (flip, hit, stand) a moment after each card, for the suspense.
                    if (jackblack.dealerMove(betId, botId).isPresent() && dealing.add(betId)) {
                        plan(now.plusMillis(1500 + random().nextInt(1500)), "jackblack-deal", () -> {
                            dealing.remove(betId);
                            if (jackblack.dealerMove(betId, botId).isEmpty()) return "SKIPPED";
                            return act(botId, jwt -> jackblack.act(jwt, betId, "deal", key()).name());
                        });
                    }
                    jackblack.turn(betId, botId).ifPresent(turn -> {
                        // One decision per situation: the same cards aren't decided twice.
                        if (!decided.add("jb-act:" + betId + ":" + turn.cards() + ":" + turn.dealerUp() + ":" + botId)) {
                            return;
                        }
                        plan(now.plusMillis(800 + random().nextInt(2500)), "jackblack-act", () -> {
                            var current = jackblack.turn(betId, botId);
                            if (current.isEmpty()) return "SKIPPED";
                            String move = JackblackBot.move(current.get());
                            return act(botId, jwt -> jackblack.act(jwt, betId, move, key()).name());
                        });
                    });
                }, array(world.botIds()));
    }

    /**
     * Hold'em: bots join open games at their tables, then play their own turns with {@link HoldemBot}'s strategy a
     * moment after it's their move.
     */
    void planHoldem(World world, Instant now) {
        for (OpenBet bet : openBets(world.tables().keySet(), now)) {
            if (!bet.mode().equals(HoldemService.MODE)) continue;
            for (Member member : world.botsIn(bet.table(), bots)) {
                if (!member.canBet()) continue;
                DemoUser bot = bots.get(member.user());
                if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("he-join", bet.id(), bot.id())) continue;
                if (random().nextDouble() >= bot.profile().joinBetChance()) continue;
                Duration delay = BotMind.delay(random(), bot.profile(),
                        Duration.between(now, bet.locksAt().minus(LOCK_MARGIN)));
                plan(now.plus(delay), "holdem-join",
                        () -> act(bot.id(), jwt -> holdem.join(jwt, bet.id(), key()).name()));
            }
        }
        if (world.botIds().isEmpty()) return;
        jdbc.query("""
                SELECT p.bet_id, p.user_id FROM card_players p JOIN bets b ON b.id = p.bet_id
                WHERE b.mode = 'holdem' AND b.status = 'PENDING' AND p.user_id = ANY (?::uuid[])
                """, rs -> {
                    UUID betId = rs.getObject("bet_id", UUID.class);
                    UUID botId = rs.getObject("user_id", UUID.class);
                    holdem.turn(betId, botId).ifPresent(turn -> {
                        // One decision per spot: the same street and bet aren't decided twice.
                        if (!decided.add("he-act:" + betId + ":" + turn.board() + ":" + turn.toCall() + ":"
                                + turn.pot() + ":" + botId)) {
                            return;
                        }
                        plan(now.plusMillis(1200 + random().nextInt(3000)), "holdem-act", () -> {
                            var current = holdem.turn(betId, botId);
                            if (current.isEmpty()) return "SKIPPED";
                            HoldemBot.Move move = HoldemBot.move(current.get(), random());
                            return act(botId, jwt -> holdem.act(jwt, betId, move.action(), move.amount(), key())
                                    .name());
                        });
                    });
                }, array(world.botIds()));
    }
}

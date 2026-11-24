package com.p2picks.app.demo;

import static com.p2picks.app.demo.DemoBots.TRASH_TALK_PER_BET;

import com.p2picks.app.chat.ChatSendService;
import com.p2picks.app.chat.GifCatalog;
import com.p2picks.app.chat.ReactionService;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.moderation.ReportService;
import com.p2picks.app.social.InvitationResponseService;
import com.p2picks.app.social.SocialMutationService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.p2picks.app.demo.BotWorld.Member;
import com.p2picks.app.demo.BotWorld.World;

/** Bots being social: chat and GIFs, reactions, trash talk after a result, complaints and blocks, follows and invitations. */
final class SocialBots extends BotPlanner {
    private final SocialMutationService social;
    private final InvitationResponseService invitations;
    private final ChatSendService chat;
    private final ReactionService reactions;
    private final GifCatalog gifs;
    private final ReportService reports;
    /** Trash-talk messages and who sent them, until someone reacts (a complaint or a block). */
    private final Map<UUID, UUID> trashTalk = new LinkedHashMap<>();
    private final Map<UUID, Integer> talkPerBet = new HashMap<>();

    SocialBots(BotKit kit, SocialMutationService social, InvitationResponseService invitations, ChatSendService chat, ReactionService reactions, GifCatalog gifs, ReportService reports) {
        super(kit);
        this.social = social;
        this.invitations = invitations;
        this.chat = chat;
        this.reactions = reactions;
        this.gifs = gifs;
        this.reports = reports;
    }

    @Override
    void forget() {
        trashTalk.clear();
        talkPerBet.clear();
    }

    /** Keeps the per-bet trash-talk counts from growing without bound. */
    void trim() {
        if (talkPerBet.size() > 10_000) talkPerBet.clear();
    }

    void planChat(World world, Instant now) {
        for (Member member : world.members()) {
            DemoUser bot = bots.get(member.user());
            if (bot == null || !member.canText() || !BotMind.online(bot.id(), bot.profile(), now)) continue;
            if (!BotMind.happens(random(), bot.profile().messagesPerHour(), tick)) continue;
            boolean gif = member.canGif() && random().nextDouble() < bot.profile().gifChance();
            plan(now, gif ? "gif" : "message", () -> say(bot.id(), member.table(), gif
                    ? new ChatSendService.Submission("gif", DemoLines.any(random(), gifs.catalog().gifs()).id())
                    : new ChatSendService.Submission("text", DemoLines.any(random(), DemoLines.CHAT))));
        }
    }

    String say(UUID bot, UUID table, ChatSendService.Submission submission) {
        return act(bot, jwt -> chat.send(jwt, table, key(), submission).outcome().name());
    }

    void planReactions(World world, Instant now) {
        if (world.tables().isEmpty()) return;
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
                                || random().nextDouble() >= bot.profile().reactionChance()) {
                            continue;
                        }
                        String emoji = DemoLines.any(random(), DemoLines.REACTIONS);
                        plan(now.plus(BotMind.delay(random(), bot.profile(), null)), "react",
                                () -> act(bot.id(), jwt -> reactions.change(jwt, message, emoji, key()).name()));
                    }
                }, array(world.tables().keySet()), Timestamp.from(now.minus(Duration.ofMinutes(10))));
    }

    /** After a bet settles, its demo participants may gloat or grumble. */
    void planTrashTalk(World world, Instant now) {
        if (world.tables().isEmpty()) return;
        jdbc.query("""
                SELECT b.id, b.table_id, b.winning_choice, p.user_id, p.choice FROM bets b
                JOIN picks p ON p.bet_id = b.id
                WHERE b.table_id = ANY (?::uuid[]) AND b.status = 'SETTLED' AND b.closed_at > ?
                """, rs -> {
                    UUID bet = rs.getObject("id", UUID.class);
                    UUID table = rs.getObject("table_id", UUID.class);
                    DemoUser bot = bots.get(rs.getObject("user_id", UUID.class));
                    if (bot == null || !decide("talk", bet, bot.id())
                            || random().nextDouble() >= bot.profile().trashTalkChance()
                            || talkPerBet.getOrDefault(bet, 0) >= TRASH_TALK_PER_BET) {
                        return;
                    }
                    talkPerBet.merge(bet, 1, Integer::sum);
                    boolean won = rs.getString("choice").equals(rs.getString("winning_choice"));
                    String line = DemoLines.any(random(), won ? DemoLines.WIN : DemoLines.LOSS);
                    plan(now.plus(BotMind.delay(random(), bot.profile(), null)), "trash-talk", () -> act(bot.id(), jwt -> {
                        var result = chat.send(jwt, table, key(), new ChatSendService.Submission("text", line));
                        if (result.message() != null) trashTalk.put(result.message().id(), bot.id());
                        return result.outcome().name();
                    }));
                }, array(world.tables().keySet()), Timestamp.from(now.minus(Duration.ofMinutes(3))));
    }

    /** Trash talk from another demo user can get them reported or blocked by table-mates who don't like it. */
    void planComplaints(World world, Instant now) {
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
                if (random().nextDouble() < bot.profile().reportChance()) {
                    plan(now.plus(BotMind.delay(random(), bot.profile(), null)), "report", () -> act(bot.id(),
                            jwt -> reports.submit(jwt, key(),
                                    new ReportService.Submission(author, "harassment", "", table, message)).name()));
                }
                if (random().nextDouble() < bot.profile().blockChance()) {
                    plan(now.plus(BotMind.delay(random(), bot.profile(), null)), "block", () -> act(bot.id(),
                            jwt -> social.mutate(jwt, author, key(), SocialMutationService.Operation.BLOCK).name()));
                }
            }
        }
    }

    void planSocial(World world, Instant now) {
        if (world.botIds().isEmpty()) return;
        // Follow-backs: only for follows made since the bots started, so the seeded world stays as seeded.
        jdbc.query("""
                SELECT f.follower_id, f.followee_id FROM follows f
                WHERE f.followee_id = ANY (?::uuid[]) AND f.created_at > ?
                  AND NOT EXISTS (SELECT 1 FROM follows r WHERE r.follower_id = f.followee_id AND r.followee_id = f.follower_id)
                """, rs -> {
                    UUID follower = rs.getObject("follower_id", UUID.class);
                    DemoUser bot = bots.get(rs.getObject("followee_id", UUID.class));
                    if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("follow-back", follower, bot.id())
                            || random().nextDouble() >= bot.profile().followBackChance()) {
                        return;
                    }
                    plan(now.plus(BotMind.delay(random(), bot.profile(), null)), "follow-back", () -> act(bot.id(),
                            jwt -> social.mutate(jwt, follower, key(), SocialMutationService.Operation.FOLLOW).name()));
                }, array(world.botIds()), Timestamp.from(kit.since()));
        // Invitations to demo users.
        jdbc.query("SELECT id, user_id FROM table_invites WHERE status = 'PENDING' AND user_id = ANY (?::uuid[])", rs -> {
            UUID invitation = rs.getObject("id", UUID.class);
            DemoUser bot = bots.get(rs.getObject("user_id", UUID.class));
            if (!BotMind.online(bot.id(), bot.profile(), now) || !decide("invite", invitation, bot.id())) return;
            boolean accept = random().nextDouble() < bot.profile().inviteAcceptChance();
            plan(now.plus(BotMind.delay(random(), bot.profile(), null)), accept ? "accept-invite" : "decline-invite",
                    () -> act(bot.id(), jwt -> invitations.respond(jwt, invitation, accept, key()).name()));
        }, array(world.botIds()));
        // Following someone new now and then: mostly other demo users, sometimes the pro.
        List<UUID> others = new ArrayList<>(world.botIds());
        for (UUID id : world.botIds()) {
            DemoUser bot = bots.get(id);
            if (!BotMind.online(id, bot.profile(), now) || !BotMind.happens(random(), bot.profile().followsPerDay() / 24, tick)) {
                continue;
            }
            UUID target = world.pro() != null && random().nextDouble() < 0.1 ? world.pro() : DemoLines.any(random(), others);
            if (target.equals(id)) continue;
            plan(now, "follow", () -> act(id, jwt -> social.mutate(jwt, target, key(), SocialMutationService.Operation.FOLLOW).name()));
        }
    }
}

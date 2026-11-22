package com.p2picks.app.bets;

import com.p2picks.app.chat.ChatMessageWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Washes every open bet an account is part of, when an admin deletes it (domain rules, "Admin account deletion"):
 * each Active or Pending bet it proposed or picked in, at a current table. Active bets lock first. Everyone's
 * wager comes back, and each table's chat says why.
 */
@Component
public class AccountBetWashes {
    static final String REASON = "A player's account was deleted";

    private final JdbcTemplate jdbc;
    private final SportsBetLoader loader;
    private final BetLockService locks;
    private final BetResolutionWriter writer;
    private final ChatMessageWriter chat;

    AccountBetWashes(JdbcTemplate jdbc, SportsBetLoader loader, BetLockService locks, BetResolutionWriter writer,
            ChatMessageWriter chat) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.locks = locks;
        this.writer = writer;
        this.chat = chat;
    }

    /**
     * Washes the account's open bets and returns how many. Every affected user (members, participants and proposers)
     * is locked first in UUID order, then each bet's table and bet, by table, the same order every
     * other bet write uses. Must run before the caller locks any table or bet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int washOpenBetsOf(UUID userId) {
        List<UUID> open = jdbc.queryForList("""
                SELECT b.id FROM bets b JOIN tables t ON t.id = b.table_id AND t.archived_at IS NULL
                WHERE b.status IN ('ACTIVE', 'PENDING')
                  AND (b.proposer_id = ? OR EXISTS (SELECT 1 FROM bet_participants p
                                                   WHERE p.bet_id = b.id AND p.user_id = ?))
                ORDER BY b.table_id, b.id
                """, UUID.class, userId, userId);
        if (open.isEmpty()) return 0;
        Set<UUID> affected = new TreeSet<>();
        for (UUID bet : open) {
            List<UUID> users = loader.affectedUsers(bet);
            if (users != null) affected.addAll(users);
        }
        String ids = "{" + String.join(",", affected.stream().map(UUID::toString).toList()) + "}";
        Set<UUID> locked = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE", UUID.class, ids));
        int washed = 0;
        for (UUID betId : open) {
            SportsBetLoader.Loaded bet = loader.load(betId, locked);
            if (bet == null) continue;
            if (bet.status().equals("ACTIVE")) {
                locks.lockNow(betId);
                bet = loader.load(betId, locked);
            }
            // Locking may already have washed it (nobody picked, or everyone picked one side).
            if (bet == null || !bet.status().equals("PENDING")) continue;
            writer.apply(bet.bet(), null, REASON, null, false);
            String question = jdbc.queryForObject("SELECT question FROM bets WHERE id = ?", String.class, betId);
            chat.system(bet.bet().tableId(), "“" + question + "” washed: a player's account was deleted");
            washed++;
        }
        return washed;
    }
}

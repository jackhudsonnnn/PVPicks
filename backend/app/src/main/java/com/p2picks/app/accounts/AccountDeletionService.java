package com.p2picks.app.accounts;

import com.p2picks.app.bets.AccountBetWashes;
import com.p2picks.app.bets.BetAccountChecks;
import com.p2picks.app.chat.ChatAccountCleanup;
import com.p2picks.app.feedback.FeedbackAccountCleanup;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.SettlementAccountChanges;
import com.p2picks.app.social.SocialAccountCleanup;
import com.p2picks.app.tables.TableAccountChanges;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.SignInCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deleting your own account. You must be square first: no Active or Pending bets, no unsettled table points and
 * no open receipts. Then personal data goes (profile, relationships, preferences, chat text and GIFs, reactions,
 * feedback, debt cap, memberships and invitations) and the account becomes "Deleted user", which bets, the ledger
 * and receipts keep referring to. Moderation records (reports, strikes) are kept. Tables you hosted are recovered.
 */
@Service
class AccountDeletionService {
    enum Outcome { DELETED, UNAUTHORIZED, WRONG_USERNAME, NOT_SQUARE }

    /** Why deletion is blocked, for the client to explain. */
    enum Blocker { OPEN_BETS, UNSETTLED_POINTS, OPEN_RECEIPTS }

    record Result(Outcome outcome, List<Blocker> blockers) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final SignInCodes codes;
    private final AccountStatus status;
    private final BetAccountChecks bets;
    private final SettlementAccountChanges settlement;
    private final TableAccountChanges tables;
    private final ChatAccountCleanup chat;
    private final SocialAccountCleanup social;
    private final FeedbackAccountCleanup feedback;
    private final AccountBetWashes washes;

    AccountDeletionService(JdbcTemplate jdbc, MutationAuthentication authentication, SignInCodes codes,
            AccountStatus status, BetAccountChecks bets, SettlementAccountChanges settlement,
            TableAccountChanges tables, ChatAccountCleanup chat, SocialAccountCleanup social,
            FeedbackAccountCleanup feedback, AccountBetWashes washes) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.codes = codes;
        this.status = status;
        this.bets = bets;
        this.settlement = settlement;
        this.tables = tables;
        this.chat = chat;
        this.social = social;
        this.feedback = feedback;
        this.washes = washes;
    }

    /**
     * Deletes the caller's account once they've typed their own username (players have no password to confirm
     * with). Refused while they're in open bets or owe or are owed anything.
     */
    @Transactional
    public Result delete(Jwt jwt, String typedUsername) {
        if (!authentication.lockActive(jwt)) return new Result(Outcome.UNAUTHORIZED, List.of());
        UUID user = UUID.fromString(jwt.getSubject());
        String username = jdbc.queryForObject("SELECT username::text FROM users WHERE id = ? AND role = 'PLAYER'",
                String.class, user);
        if (username == null || typedUsername == null
                || !username.equalsIgnoreCase(PostgresText.trim(typedUsername))) {
            return new Result(Outcome.WRONG_USERNAME, List.of());
        }
        List<Blocker> blockers = new ArrayList<>();
        if (bets.inOpenBets(user)) blockers.add(Blocker.OPEN_BETS);
        if (bets.hasUnsettledPoints(user)) blockers.add(Blocker.UNSETTLED_POINTS);
        if (settlement.hasOpenReceipts(user)) blockers.add(Blocker.OPEN_RECEIPTS);
        if (!blockers.isEmpty()) return new Result(Outcome.NOT_SQUARE, List.copyOf(blockers));

        erase(user);
        return new Result(Outcome.DELETED, List.of());
    }

    /** What the admin's delete reports: how many open bets it washed. */
    record AdminResult(Outcome outcome, int washedBets) {}

    /**
     * The admin deletes a player outright (domain rules, "Admin account deletion"), with no need to be square:
     * every open bet they proposed or picked in is washed for everyone, then their account is deleted as if they'd
     * done it themselves. Points already won or lost, settle-ups and receipts stay, under "Deleted user". Their
     * username becomes free. Returns {@code UNAUTHORIZED} for a bad admin session and {@code WRONG_USERNAME} when
     * there's no such player.
     */
    @Transactional
    public AdminResult deleteByAdmin(Jwt adminJwt, UUID player) {
        if (!authentication.lockActive(adminJwt)) return new AdminResult(Outcome.UNAUTHORIZED, 0);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND role = 'PLAYER' AND status IN ('ACTIVE', 'FROZEN'))
                """, Boolean.class, player);
        if (!Boolean.TRUE.equals(exists)) return new AdminResult(Outcome.WRONG_USERNAME, 0);
        int washed = washes.washOpenBetsOf(player);
        // Locked after the bets: their order puts users first, and this row is already locked if they had any.
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, player);
        erase(player);
        authentication.requireUnexpired(adminJwt);
        return new AdminResult(Outcome.DELETED, washed);
    }

    /** Removes the account's personal data, ends its sessions and marks it deleted, freeing the username. */
    private void erase(UUID user) {
        tables.deleted(user);
        chat.deleted(user);
        social.deleted(user);
        feedback.deleted(user);
        settlement.deleted(user);
        status.revokeSessions(user);
        codes.forget(user);
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = clock_timestamp(), username = NULL, hue = NULL,
                                 password_hash = NULL
                WHERE id = ?
                """, user);
    }
}

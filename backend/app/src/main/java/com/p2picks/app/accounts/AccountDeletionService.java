package com.p2picks.app.accounts;

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
import org.springframework.security.crypto.password.PasswordEncoder;
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
    enum Outcome { DELETED, UNAUTHORIZED, WRONG_PASSWORD, NOT_SQUARE }

    /** Why deletion is blocked, for the client to explain. */
    enum Blocker { OPEN_BETS, UNSETTLED_POINTS, OPEN_RECEIPTS }

    record Result(Outcome outcome, List<Blocker> blockers) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final PasswordEncoder passwords;
    private final AccountStatus status;
    private final BetAccountChecks bets;
    private final SettlementAccountChanges settlement;
    private final TableAccountChanges tables;
    private final ChatAccountCleanup chat;
    private final SocialAccountCleanup social;
    private final FeedbackAccountCleanup feedback;

    AccountDeletionService(JdbcTemplate jdbc, MutationAuthentication authentication, PasswordEncoder passwords,
            AccountStatus status, BetAccountChecks bets, SettlementAccountChanges settlement,
            TableAccountChanges tables, ChatAccountCleanup chat, SocialAccountCleanup social,
            FeedbackAccountCleanup feedback) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.passwords = passwords;
        this.status = status;
        this.bets = bets;
        this.settlement = settlement;
        this.tables = tables;
        this.chat = chat;
        this.social = social;
        this.feedback = feedback;
    }

    @Transactional
    public Result delete(Jwt jwt, String password) {
        if (!authentication.lockActive(jwt)) return new Result(Outcome.UNAUTHORIZED, List.of());
        UUID user = UUID.fromString(jwt.getSubject());
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ? AND role = 'PLAYER'",
                String.class, user);
        if (hash == null || !passwords.matches(password, hash)) return new Result(Outcome.WRONG_PASSWORD, List.of());
        List<Blocker> blockers = new ArrayList<>();
        if (bets.inOpenBets(user)) blockers.add(Blocker.OPEN_BETS);
        if (bets.hasUnsettledPoints(user)) blockers.add(Blocker.UNSETTLED_POINTS);
        if (settlement.hasOpenReceipts(user)) blockers.add(Blocker.OPEN_RECEIPTS);
        if (!blockers.isEmpty()) return new Result(Outcome.NOT_SQUARE, List.copyOf(blockers));

        tables.deleted(user);
        chat.deleted(user);
        social.deleted(user);
        feedback.deleted(user);
        settlement.deleted(user);
        status.revokeSessions(user);
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = clock_timestamp(), username = NULL, hue = NULL,
                                 password_hash = NULL
                WHERE id = ?
                """, user);
        return new Result(Outcome.DELETED, List.of());
    }
}

package com.p2picks.app.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Redeeming setup and reset codes, and changing your own password (LAN plan, "Accounts and passwords"). */
@Service
class PasswordService {
    sealed interface SetupResult {
        record SignedIn(String token) implements SetupResult {}

        record InvalidCode() implements SetupResult {}

        record Rejected(String problem) implements SetupResult {}
    }

    enum ChangeResult { CHANGED, WRONG_PASSWORD, UNAUTHORIZED }

    private final AccountSetupCodes codes;
    private final DevelopmentSessions sessions;
    private final MutationAuthentication authentication;
    private final JdbcTemplate jdbc;

    PasswordService(AccountSetupCodes codes, DevelopmentSessions sessions, MutationAuthentication authentication,
            JdbcTemplate jdbc) {
        this.codes = codes;
        this.sessions = sessions;
        this.authentication = authentication;
        this.jdbc = jdbc;
    }

    /**
     * Sets the password for the code's account and signs in, ending its other sessions. A weak password leaves the
     * code unused, so the player can try again.
     */
    @Transactional
    SetupResult redeem(String code, String password, String previousToken) {
        Optional<AccountSetupCodes.Holder> holder = codes.lockValid(code);
        if (holder.isEmpty()) return new SetupResult.InvalidCode();
        Optional<String> problem = PasswordPolicy.problem(holder.get().username(), password);
        if (problem.isPresent()) return new SetupResult.Rejected(problem.get());
        codes.markUsed(holder.get().codeId());
        return new SetupResult.SignedIn(
                sessions.replacePasswordAndSignIn(holder.get().userId(), password, previousToken));
    }

    /** Checks the policy first so a weak new password doesn't count as a failed attempt. */
    Optional<String> problem(Jwt jwt, String password) {
        String username = jdbc.query("SELECT username::text FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, UUID.fromString(jwt.getSubject()));
        return PasswordPolicy.problem(username, password);
    }

    @Transactional
    ChangeResult change(Jwt jwt, String current, String next) {
        if (!authentication.lockActive(jwt)) return ChangeResult.UNAUTHORIZED;
        UUID user = UUID.fromString(jwt.getSubject());
        if (!sessions.matches(user, current)) return ChangeResult.WRONG_PASSWORD;
        sessions.changePassword(user, UUID.fromString(jwt.getId()), next);
        authentication.requireUnexpired(jwt);
        return ChangeResult.CHANGED;
    }
}

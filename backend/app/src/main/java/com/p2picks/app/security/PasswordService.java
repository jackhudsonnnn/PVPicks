package com.p2picks.app.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Changing your own password. Only admins have passwords; players sign in with a code ({@link SignInCodes}). */
@Service
class PasswordService {
    enum ChangeResult { CHANGED, WRONG_PASSWORD, UNAUTHORIZED, NOT_ADMIN }

    private final DevelopmentSessions sessions;
    private final MutationAuthentication authentication;
    private final JdbcTemplate jdbc;

    PasswordService(DevelopmentSessions sessions, MutationAuthentication authentication, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.jdbc = jdbc;
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
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT role = 'ADMIN' FROM users WHERE id = ?", Boolean.class,
                user))) {
            return ChangeResult.NOT_ADMIN;
        }
        if (!sessions.matches(user, current)) return ChangeResult.WRONG_PASSWORD;
        sessions.changePassword(user, UUID.fromString(jwt.getId()), next);
        authentication.requireUnexpired(jwt);
        return ChangeResult.CHANGED;
    }
}

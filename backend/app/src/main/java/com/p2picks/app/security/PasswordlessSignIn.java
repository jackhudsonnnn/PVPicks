package com.p2picks.app.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

/**
 * Signs in as any account without a password: the dev demo's sign-in picker (docs/site/demo-data.md). Only exists
 * when {@code pvpicks.demo.enabled} is true, which only the dev profile sets.
 */
@Service
@ConditionalOnProperty(name = "pvpicks.demo.enabled", havingValue = "true")
public class PasswordlessSignIn {
    public enum Result { SIGNED_IN, NO_SUCH_ACCOUNT, FROZEN }

    private final DevelopmentSessions sessions;
    private final CsrfTokenRepository csrfTokens;

    PasswordlessSignIn(DevelopmentSessions sessions, CsrfTokenRepository csrfTokens) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
    }

    /** Starts a session for the account, replacing the caller's current one, and rotates the CSRF token. */
    public Result signIn(UUID userId, HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        String previousToken = cookies == null ? null : Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals("session"))
                .map(Cookie::getValue).findFirst().orElse(null);
        String token;
        try {
            token = sessions.signInWithoutPassword(userId, previousToken);
        } catch (DevelopmentSessions.Frozen frozen) {
            return Result.FROZEN;
        }
        if (token == null) return Result.NO_SUCH_ACCOUNT;
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.session(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return Result.SIGNED_IN;
    }

    /** Ends the caller's cookie session and rotates the CSRF token, e.g. after a reset wiped every session. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.cleared());
        csrfTokens.saveToken(null, request, response);
    }
}

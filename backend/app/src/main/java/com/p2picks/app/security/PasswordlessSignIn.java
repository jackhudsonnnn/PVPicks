package com.p2picks.app.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

    private final Sessions sessions;
    private final CsrfTokenRepository csrfTokens;
    private final JwtDecoder decoder;
    private final SessionCookies cookies;

    PasswordlessSignIn(Sessions sessions, CsrfTokenRepository csrfTokens, JwtDecoder decoder,
            SessionCookies cookies) {
        this.cookies = cookies;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.decoder = decoder;
    }

    /**
     * A fresh session for an account, as the decoded token a signed-in request would carry: what the demo's bots act
     * with, so every service checks them like any player. Null when there's no such active account.
     */
    public Jwt session(UUID userId) {
        try {
            String token = sessions.signInWithoutPassword(userId, null);
            return token == null ? null : decoder.decode(token);
        } catch (Sessions.Frozen frozen) {
            return null;
        }
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
        } catch (Sessions.Frozen frozen) {
            return Result.FROZEN;
        }
        if (token == null) return Result.NO_SUCH_ACCOUNT;
        response.addHeader(HttpHeaders.SET_COOKIE, this.cookies.session(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return Result.SIGNED_IN;
    }

    /** Ends the caller's cookie session and rotates the CSRF token, e.g. after a reset wiped every session. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.cleared());
        csrfTokens.saveToken(null, request, response);
    }
}

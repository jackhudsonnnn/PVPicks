package com.p2picks.app.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The session cookie's attributes in one place, for endpoints outside this module that end a session. Cookies are
 * {@code Secure} when {@code pvpicks.secure-cookies} is on (the lan profile, behind HTTPS); dev uses plain HTTP.
 */
@Component
public class SessionCookies {
    private final boolean secure;

    SessionCookies(@Value("${pvpicks.secure-cookies:false}") boolean secure) {
        this.secure = secure;
    }

    String session(String value, Duration maxAge) {
        return ResponseCookie.from("session", value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/").maxAge(maxAge).build().toString();
    }

    /** A Set-Cookie value that clears the session cookie. */
    public String cleared() {
        return session("", Duration.ZERO);
    }

    boolean secure() {
        return secure;
    }
}

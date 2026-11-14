package com.p2picks.app.security;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** The session cookie's attributes in one place, for endpoints outside this module that end a session. */
public final class SessionCookies {
    private SessionCookies() {}

    static String session(String value, Duration maxAge) {
        return ResponseCookie.from("session", value).httpOnly(true).secure(false)
                .sameSite("Strict").path("/").maxAge(maxAge).build().toString();
    }

    /** A Set-Cookie value that clears the session cookie. */
    public static String cleared() {
        return session("", Duration.ZERO);
    }
}

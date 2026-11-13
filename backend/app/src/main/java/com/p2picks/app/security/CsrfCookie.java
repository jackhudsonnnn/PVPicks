package com.p2picks.app.security;

/** Names of the CSRF cookie and request header shared by HTTP mutations and the realtime CONNECT frame. */
public final class CsrfCookie {
    public static final String COOKIE = "XSRF-TOKEN";
    public static final String HEADER = "X-CSRF-TOKEN";
    public static final String PARAMETER = "_csrf";

    private CsrfCookie() {}
}

package com.p2picks.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class OriginFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final Set<String> allowedOrigins;

    /**
     * Rejects cross-origin writes: only the allowed origin may POST, PUT or DELETE. Dev accepts an HTTP loopback origin
     * (as 127.0.0.1 or localhost, same port); the lan profile requires an HTTPS origin (the LAN hostname Caddy
     * serves), because its cookies are Secure.
     */
    OriginFilter(String allowedOrigin, boolean lan) {
        URI origin = URI.create(allowedOrigin);
        boolean shape = origin.getHost() != null && origin.getUserInfo() == null && origin.getRawPath() != null
                && origin.getRawPath().isEmpty() && origin.getQuery() == null && origin.getFragment() == null;
        boolean allowed = lan ? "https".equals(origin.getScheme())
                : "http".equals(origin.getScheme())
                        && ("127.0.0.1".equals(origin.getHost()) || "localhost".equals(origin.getHost()));
        if (!shape || !allowed) {
            throw new IllegalArgumentException(lan ? "PVPICKS_ORIGIN must be the HTTPS origin players use"
                    : "PVPICKS_DEV_ORIGIN must be an HTTP loopback origin");
        }
        // Dev: 127.0.0.1 and localhost are the same loopback address, and Vite prints the localhost one, so accept
        // both on the configured port. LAN accepts only its one HTTPS origin.
        if (lan) {
            this.allowedOrigins = Set.of(allowedOrigin);
        } else {
            String port = origin.getPort() < 0 ? "" : ":" + origin.getPort();
            this.allowedOrigins = Set.of("http://127.0.0.1" + port, "http://localhost" + port);
        }
    }

    /** A missing Origin is refused (immutable sets throw on contains(null)). */
    private boolean allowed(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && !allowed(request.getHeader("Origin"))) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"forbidden\",\"message\":\"Request forbidden.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

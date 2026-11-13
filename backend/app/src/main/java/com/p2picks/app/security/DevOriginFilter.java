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

final class DevOriginFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final String allowedOrigin;

    DevOriginFilter(String allowedOrigin) {
        URI origin = URI.create(allowedOrigin);
        if (!"http".equals(origin.getScheme())
                || !("127.0.0.1".equals(origin.getHost()) || "localhost".equals(origin.getHost()))
                || origin.getUserInfo() != null || origin.getRawPath() == null || !origin.getRawPath().isEmpty()
                || origin.getQuery() != null || origin.getFragment() != null) {
            throw new IllegalArgumentException("PVPICKS_DEV_ORIGIN must be an HTTP loopback origin");
        }
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && !allowedOrigin.equals(request.getHeader("Origin"))) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"forbidden\",\"message\":\"Request forbidden.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

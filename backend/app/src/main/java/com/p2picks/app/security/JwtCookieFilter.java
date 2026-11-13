package com.p2picks.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

final class JwtCookieFilter extends OncePerRequestFilter {
    private final JwtDecoder decoder;
    private final AuthenticationEntryPoint unauthorized;

    JwtCookieFilter(JwtDecoder decoder, AuthenticationEntryPoint unauthorized) {
        this.decoder = decoder;
        this.unauthorized = unauthorized;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.equals("/auth/login") || path.equals("/auth/csrf") || path.equals("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }
        Cookie[] cookies = request.getCookies();
        List<String> tokens = cookies == null ? List.of() : Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals("session"))
                .map(Cookie::getValue).toList();
        if (tokens.size() > 1) {
            unauthorized.commence(request, response, new InsufficientAuthenticationException("Ambiguous session"));
            return;
        }
        if (tokens.size() == 1) {
            try {
                Jwt jwt = decoder.decode(tokens.getFirst());
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
            } catch (JwtException exception) {
                unauthorized.commence(request, response, new InsufficientAuthenticationException("Invalid session", exception));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

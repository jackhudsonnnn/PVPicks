package com.p2picks.app.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Times every HTTP request on the server, outermost, so security rejections and rate limits count too. A request
 * is filed under its handler's route pattern ({@code /tables/{tableId}/bets}); one that never reached a handler
 * (refused by security, or no such path) is filed under {@link #NO_ROUTE}, so paths never become routes. The
 * WebSocket, health and docs endpoints aren't timed.
 */
@Configuration
class PerfFilter extends OncePerRequestFilter {
    static final String NO_ROUTE = "(no route)";

    private final PerfRecorder recorder;

    PerfFilter(PerfRecorder recorder) {
        this.recorder = recorder;
    }

    @Bean
    FilterRegistrationBean<PerfFilter> perfFilterRegistration() {
        FilterRegistrationBean<PerfFilter> registration = new FilterRegistrationBean<>(this);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/ws") || path.startsWith("/ws/") || path.startsWith("/actuator")
                || path.equals("/docs") || path.startsWith("/docs/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern instanceof String text && !text.equals("/**") ? text : NO_ROUTE;
            // An exception escaping the chain becomes a 500 after this filter returns.
            int status = failed ? 500 : response.getStatus();
            recorder.record(PerfRecorder.Source.SERVER, request.getMethod(), route, status,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }
}

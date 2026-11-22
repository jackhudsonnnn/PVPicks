package com.p2picks.app.delivery;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Stores the browser's current CSRF token (from the CSRF cookie) with the WebSocket session so the STOMP CONNECT
 * frame can be checked against it. A handshake without the cookie is refused: clients call
 * {@code GET /auth/csrf} first, as for HTTP mutations.
 */
@Component
class CsrfHandshake implements HandshakeInterceptor {
    private final CsrfTokenRepository csrfTokens;

    CsrfHandshake(CsrfTokenRepository csrfTokens) {
        this.csrfTokens = csrfTokens;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        CsrfToken token = request instanceof ServletServerHttpRequest servlet
                ? csrfTokens.loadToken(servlet.getServletRequest()) : null;
        if (token == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(CsrfToken.class.getName(), token);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {}
}

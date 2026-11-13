package com.p2picks.app.delivery;

import com.p2picks.app.security.CsrfCookie;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.WebUtils;

/**
 * Stores the CSRF token from the cookie sent with the WebSocket handshake, so the STOMP CONNECT frame can be
 * checked against it. A handshake without the cookie is refused: clients call {@code GET /auth/csrf} first, as
 * for HTTP mutations. The cookie is read directly because the security chain rotates the CSRF token on every
 * authenticated request, which hides the incoming token from the repository during the handshake itself.
 */
@Component
class CsrfHandshake implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Map<String, Object> attributes) {
        Cookie cookie = request instanceof ServletServerHttpRequest servlet
                ? WebUtils.getCookie(servlet.getServletRequest(), CsrfCookie.COOKIE) : null;
        if (cookie == null || !StringUtils.hasText(cookie.getValue())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(CsrfToken.class.getName(),
                new DefaultCsrfToken(CsrfCookie.HEADER, CsrfCookie.PARAMETER, cookie.getValue()));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
            Exception exception) {}
}

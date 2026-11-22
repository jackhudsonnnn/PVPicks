package com.p2picks.app.delivery;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Tracks this instance's WebSocket connections and the login session behind each one, and closes any whose
 * login session has expired, been revoked or whose account is no longer an active player.
 */
@Component
class RealtimeSessions {
    private static final Logger log = LoggerFactory.getLogger(RealtimeSessions.class);

    private final RealtimeAccess access;
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, Jwt> logins = new ConcurrentHashMap<>();

    RealtimeSessions(RealtimeAccess access) {
        this.access = access;
    }

    WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sockets.remove(session.getId());
                logins.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    void connected(String socketId, Jwt login) {
        logins.put(socketId, login);
    }

    /** The subset of the given sockets whose login is still valid; invalid ones are closed. */
    Set<String> valid(Collection<String> socketIds) {
        Map<String, Jwt> candidates = new java.util.HashMap<>();
        for (String id : socketIds) {
            Jwt login = logins.get(id);
            if (login != null) candidates.put(id, login);
        }
        Instant now = Instant.now();
        List<AccessIdentity> identities = candidates.entrySet().stream()
                .filter(entry -> entry.getValue().getExpiresAt() == null || entry.getValue().getExpiresAt().isAfter(now))
                .map(entry -> AccessIdentity.of(entry.getKey(), entry.getValue()))
                .filter(identity -> identity != null)
                .toList();
        Set<UUID> active = access.activeSessions(identities.stream().map(AccessIdentity::identity).toList());
        Set<String> valid = new java.util.HashSet<>();
        for (AccessIdentity identity : identities) {
            if (active.contains(identity.identity().sessionId())) valid.add(identity.socketId());
        }
        for (String id : socketIds) {
            if (!valid.contains(id)) close(id);
        }
        return valid;
    }

    /** Closes connections whose login is no longer valid, so revocation does not wait for the next event. */
    @Scheduled(fixedDelay = 15_000)
    void sweep() {
        try {
            // Only connected STOMP sessions: a socket that has not sent CONNECT yet has no login to check.
            valid(List.copyOf(logins.keySet()));
        } catch (RuntimeException exception) {
            log.warn("Realtime session sweep failed", exception);
        }
    }

    private void close(String socketId) {
        logins.remove(socketId);
        WebSocketSession socket = sockets.remove(socketId);
        if (socket == null || !socket.isOpen()) return;
        try {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("Session ended"));
        } catch (IOException exception) {
            log.debug("Could not close realtime session {}", socketId, exception);
        }
    }

    private record AccessIdentity(String socketId, RealtimeAccess.SessionIdentity identity) {
        static AccessIdentity of(String socketId, Jwt login) {
            try {
                return new AccessIdentity(socketId, new RealtimeAccess.SessionIdentity(
                        UUID.fromString(login.getId()), UUID.fromString(login.getSubject())));
            } catch (IllegalArgumentException | NullPointerException exception) {
                return null;
            }
        }
    }
}

package com.p2picks.app.delivery;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authorizes inbound STOMP frames. CSRF is checked on CONNECT before this runs. Clients may only subscribe to
 * their own personal and settlement feeds or to the feeds of a table they currently belong to, and may never
 * SEND.
 */
@Component
class SubscriptionGuard implements ChannelInterceptor {
    static final Pattern TABLE_FEED = Pattern.compile("^/user/queue/tables/([0-9a-fA-F-]{36})(/bets|/chat)?$");
    static final Set<String> PERSONAL_FEEDS = Set.of(
            "/user" + RealtimeGateway.SETTLEMENTS_DESTINATION, "/user" + RealtimeGateway.PERSONAL_DESTINATION,
            "/user" + RealtimeGateway.TICKER_DESTINATION);

    private final RealtimeSessions sessions;
    private final RealtimeAccess access;

    SubscriptionGuard(RealtimeSessions sessions, RealtimeAccess access) {
        this.sessions = sessions;
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) return message;
        StompCommand command = stomp.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            sessions.connected(stomp.getSessionId(), login(stomp));
        } else if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscription(stomp);
        } else if (command == StompCommand.SEND) {
            throw new AccessDeniedException("Clients cannot publish messages.");
        }
        return message;
    }

    private void authorizeSubscription(StompHeaderAccessor stomp) {
        UUID userId = UUID.fromString(login(stomp).getSubject());
        if (sessions.valid(Set.of(stomp.getSessionId())).isEmpty()) {
            throw new AccessDeniedException("The session has ended.");
        }
        String destination = stomp.getDestination();
        if (destination != null && PERSONAL_FEEDS.contains(destination)) return;
        Matcher table = destination == null ? null : TABLE_FEED.matcher(destination);
        if (table != null && table.matches()) {
            UUID tableId;
            try {
                tableId = UUID.fromString(table.group(1));
            } catch (IllegalArgumentException exception) {
                throw new AccessDeniedException("Unknown destination.");
            }
            if (access.currentMembers(tableId, Set.of(userId)).contains(userId)) return;
        }
        throw new AccessDeniedException("Subscription not permitted.");
    }

    private static Jwt login(StompHeaderAccessor stomp) {
        if (stomp.getUser() instanceof JwtAuthenticationToken authentication) return authentication.getToken();
        throw new AccessDeniedException("Authentication required.");
    }
}

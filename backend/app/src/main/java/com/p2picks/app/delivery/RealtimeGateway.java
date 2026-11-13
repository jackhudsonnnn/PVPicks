package com.p2picks.app.delivery;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

/**
 * Sends invalidations to this instance's subscribers. There are no shared broadcast topics: every message
 * goes to one WebSocket session after rechecking that its login is valid and, for table feeds, that its user
 * is still a current member. A missed revocation therefore cannot keep leaking updates.
 */
@Component
class RealtimeGateway {
    static final String TABLE_DESTINATION = "/queue/tables/%s%s";
    static final String SETTLEMENTS_DESTINATION = "/queue/settlements";
    static final String PERSONAL_DESTINATION = "/queue/me";
    /** Sports scores only, the same for every signed-in player. */
    static final String TICKER_DESTINATION = "/queue/ticker";

    /** A table's feeds: its bets, its chat, and the table view itself (settings, hosts, members). */
    enum TableFeed {
        BETS("/bets"), CHAT("/chat"), VIEW("");

        final String suffix;

        TableFeed(String suffix) {
            this.suffix = suffix;
        }
    }

    private final SimpMessagingTemplate messaging;
    private final SimpUserRegistry users;
    private final RealtimeSessions sessions;
    private final RealtimeAccess access;

    RealtimeGateway(SimpMessagingTemplate messaging, SimpUserRegistry users, RealtimeSessions sessions,
            RealtimeAccess access) {
        this.messaging = messaging;
        this.users = users;
        this.sessions = sessions;
        this.access = access;
    }

    /**
     * Sends to current members subscribed to the table feed; when {@code onlyUsers} is not null, only to those
     * of them in that set.
     */
    void toTable(UUID tableId, TableFeed feed, Invalidation invalidation, Set<UUID> onlyUsers) {
        String destination = TABLE_DESTINATION.formatted(tableId, feed.suffix);
        Map<String, String> subscribers = subscribers(destination, null);
        if (subscribers.isEmpty()) return;
        Set<UUID> userIds = new HashSet<>();
        subscribers.values().forEach(user -> userIds.add(UUID.fromString(user)));
        Set<UUID> members = access.currentMembers(tableId, userIds);
        send(destination, invalidation, subscribers, user -> {
            UUID id = UUID.fromString(user);
            return members.contains(id) && (onlyUsers == null || onlyUsers.contains(id));
        });
    }

    /** Sends to the user's own subscriptions of a personal destination. */
    void toUser(UUID userId, String destination, Invalidation invalidation) {
        Map<String, String> subscribers = subscribers(destination, userId.toString());
        send(destination, invalidation, subscribers, user -> true);
    }

    /** Sends to every subscriber of a destination whose login is still valid. */
    void toSubscribers(String destination, Invalidation invalidation) {
        send(destination, invalidation, subscribers(destination, null), user -> true);
    }

    /** Socket ID to user name for subscriptions to the user destination, optionally for one user. */
    private Map<String, String> subscribers(String destination, String onlyUser) {
        String subscribed = "/user" + destination;
        Map<String, String> result = new HashMap<>();
        for (SimpSubscription subscription : users.findSubscriptions(s -> s.getDestination().equals(subscribed))) {
            String user = subscription.getSession().getUser().getName();
            if (onlyUser == null || onlyUser.equals(user)) result.put(subscription.getSession().getId(), user);
        }
        return result;
    }

    private void send(String destination, Invalidation invalidation, Map<String, String> subscribers,
            java.util.function.Predicate<String> allowed) {
        if (subscribers.isEmpty()) return;
        Set<String> valid = sessions.valid(subscribers.keySet());
        subscribers.forEach((socketId, user) -> {
            if (!valid.contains(socketId) || !allowed.test(user)) return;
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(socketId);
            headers.setLeaveMutable(true);
            messaging.convertAndSendToUser(user, destination, invalidation, headers.getMessageHeaders());
        });
    }
}

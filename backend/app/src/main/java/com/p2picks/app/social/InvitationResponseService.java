package com.p2picks.app.social;

import com.p2picks.app.chat.ChatMessageWriter;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationResponseService {
    public enum Outcome { UPDATED, REPLAYED, CONFLICT, STALE, UNAUTHORIZED, NOT_FOUND }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final SocialRelationshipQueries relationships;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;
    private final ChatMessageWriter chatMessages;

    InvitationResponseService(JdbcTemplate jdbc, MutationAuthentication authentication,
            SocialRelationshipQueries relationships, IdempotencyGuard idempotency, Outbox outbox,
            ChatMessageWriter chatMessages) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.relationships = relationships;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.chatMessages = chatMessages;
    }

    @Transactional
    public Outcome respond(Jwt jwt, UUID invitationId, boolean accept, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Invitation> hints = jdbc.query("""
                SELECT from_id, table_id, status FROM table_invites WHERE id = ? AND user_id = ?
                """, (rs, row) -> new Invitation(rs.getObject("from_id", UUID.class),
                        rs.getObject("table_id", UUID.class), rs.getString("status")), invitationId, userId);
        if (hints.isEmpty()) {
            if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        Invitation hint = hints.getFirst();
        MutationAuthentication.PairStatus pair = authentication.lockActivePair(jwt, hint.sender());
        if (pair == MutationAuthentication.PairStatus.UNAUTHORIZED) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (pair == MutationAuthentication.PairStatus.TARGET_UNAVAILABLE) return Outcome.NOT_FOUND;
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), hint.table());
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Invitation> locked = jdbc.query("""
                SELECT from_id, table_id, status FROM table_invites WHERE id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new Invitation(rs.getObject("from_id", UUID.class),
                        rs.getObject("table_id", UUID.class), rs.getString("status")), invitationId, userId);
        if (locked.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        Invitation invitation = locked.getFirst();
        if (!invitation.sender().equals(hint.sender()) || !invitation.table().equals(hint.table())) {
            throw new IllegalStateException("Invitation identity changed during response");
        }
        boolean member = isMember(invitation.table(), userId);
        if (relationships.blocked(userId, invitation.sender())
                || invitation.status().equals("PENDING") && member
                || invitation.status().equals("ACCEPTED") && !member) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "invitation.respond.v1",
                invitationId + "|" + accept);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!invitation.status().equals("PENDING")
                || !eligible(invitation.table(), userId, invitation.sender())) {
            if (invitation.status().equals("PENDING")) {
                jdbc.update("UPDATE table_invites SET status = 'CANCELLED' WHERE id = ?", invitationId);
            }
            idempotency.discardNewClaim(userId, key);
            authentication.requireUnexpired(jwt);
            return Outcome.STALE;
        }
        jdbc.update("UPDATE table_invites SET status = ? WHERE id = ?",
                accept ? "ACCEPTED" : "DECLINED", invitationId);
        if (accept) {
            jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                    invitation.table(), userId);
            String username = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT username::text FROM users WHERE id = ?", String.class, userId));
            outbox.append(OutboxEvent.TABLE_MEMBER_JOINED, invitation.table(),
                    Map.of("tableId", invitation.table(), "userId", userId));
            chatMessages.system(invitation.table(), username + " joined the table");
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private boolean isMember(UUID table, UUID user) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM table_members WHERE table_id = ? AND user_id = ?)
                """, Boolean.class, table, user));
    }

    private boolean eligible(UUID table, UUID invitee, UUID sender) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM table_members WHERE table_id = ? AND user_id = ? AND role = 'HOST')
                    AND EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?)
                    AND (CASE WHEN EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?)
                        THEN COALESCE((SELECT friends FROM invite_preferences WHERE user_id = ?), 'open')
                        ELSE COALESCE((SELECT following FROM invite_preferences WHERE user_id = ?), 'request')
                        END) <> 'closed'
                """, Boolean.class, table, sender, invitee, sender, sender, invitee, invitee, invitee));
    }

    private record Invitation(UUID sender, UUID table, String status) {}
}

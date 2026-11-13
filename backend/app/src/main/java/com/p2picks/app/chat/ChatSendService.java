package com.p2picks.app.chat;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatSendService {
    private static final String OPERATION = "chat.message.send.v1";

    public enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    public record Submission(String kind, String content) {}
    public record Result(Outcome outcome, ChatWindowService.Message message) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;

    ChatSendService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
    }

    @Transactional
    public Result send(Jwt jwt, UUID tableId, String key, Submission submission) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.NOT_FOUND, null);
        }
        List<Boolean> permissions = jdbc.query("""
                SELECT CASE WHEN ? = 'text' THEN can_text ELSE can_send_gifs END AS allowed
                FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getBoolean("allowed"), submission.kind(), tableId, userId);
        if (permissions.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.NOT_FOUND, null);
        }
        if (!permissions.getFirst()) {
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.FORBIDDEN, null);
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, OPERATION,
                tableId + "|" + submission.kind() + "|" + submission.content(), IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        UUID messageId;
        OffsetDateTime createdAt;
        Outcome outcome;
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            messageId = idempotency.responseReference(userId, key, OPERATION);
            List<OffsetDateTime> times = jdbc.query("""
                    SELECT created_at FROM chat_messages
                    WHERE id = ? AND table_id = ? AND author_id = ? AND kind = ?
                      AND (text = ? OR gif_id = ?) FOR SHARE
                    """, (rs, row) -> rs.getObject("created_at", OffsetDateTime.class),
                    messageId, tableId, userId, submission.kind(), submission.content(), submission.content());
            if (times.isEmpty()) {
                authentication.requireUnexpired(jwt);
                return new Result(Outcome.NOT_FOUND, null);
            }
            createdAt = times.getFirst();
            outcome = Outcome.REPLAYED;
        } else {
            messageId = UUID.randomUUID();
            createdAt = jdbc.queryForObject("""
                    INSERT INTO chat_messages (id, table_id, kind, author_id, text, gif_id)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING created_at
                    """, OffsetDateTime.class, messageId, tableId, submission.kind(), userId,
                    submission.kind().equals("text") ? submission.content() : null,
                    submission.kind().equals("gif") ? submission.content() : null);
            if (createdAt == null) throw new IllegalStateException("Created chat message has no timestamp");
            idempotency.saveResponseReference(userId, key, OPERATION, messageId);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'chat', ?, 'chat.message.created.v1',
                            jsonb_build_object('tableId', ?::text, 'messageId', ?::text, 'kind', ?::text))
                    """, UUID.randomUUID(), tableId.toString(), tableId.toString(), messageId.toString(),
                    submission.kind());
            outcome = Outcome.CREATED;
        }
        authentication.requireUnexpired(jwt);
        return new Result(outcome, new ChatWindowService.Message(messageId, tableId, submission.kind(), createdAt,
                userId, submission.kind().equals("text") ? submission.content() : null,
                submission.kind().equals("gif") ? submission.content() : null, null, null, null, List.of()));
    }
}

package com.p2picks.app.chat;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat polls (domain rules, "Polls"). A member with the poll permission asks a question with 2–6 options while the
 * table has polls on. Every member can vote for one option and change or remove the vote at any time; polls never
 * close. Turning polls off (or taking the permission) stops new polls only: existing polls keep taking votes.
 */
@Service
public class PollService {
    public enum Outcome { CREATED, REPLAYED, UPDATED, CONFLICT, UNAUTHORIZED, FORBIDDEN, POLLS_OFF, NOT_FOUND,
        BAD_OPTION }
    public record Created(Outcome outcome, ChatWindowService.Message message) {}

    private static final String CREATE = "chat.poll.create.v1";

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    PollService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    private record Access(boolean canPoll, boolean pollsEnabled) {}

    @Transactional
    public Created create(Jwt jwt, UUID tableId, String key, String question, List<String> options) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return new Created(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        List<Access> access = jdbc.query("""
                SELECT member.can_poll, t.polls_enabled FROM tables t
                JOIN table_members member ON member.table_id = t.id AND member.user_id = ?
                WHERE t.id = ? AND t.archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Access(rs.getBoolean("can_poll"), rs.getBoolean("polls_enabled")),
                userId, tableId);
        authentication.requireUnexpired(jwt);
        if (access.isEmpty()) return new Created(Outcome.NOT_FOUND, null);
        if (!access.getFirst().pollsEnabled()) return new Created(Outcome.POLLS_OFF, null);
        if (!access.getFirst().canPoll()) return new Created(Outcome.FORBIDDEN, null);
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, CREATE,
                tableId + "|" + question + "|" + String.join("\u001f", options),
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Created(Outcome.CONFLICT, null);
        UUID messageId;
        OffsetDateTime createdAt;
        Outcome outcome;
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            messageId = idempotency.responseReference(userId, key, CREATE);
            List<OffsetDateTime> times = jdbc.query("""
                    SELECT created_at FROM chat_messages
                    WHERE id = ? AND table_id = ? AND author_id = ? AND kind = 'poll' FOR SHARE
                    """, (rs, row) -> rs.getObject("created_at", OffsetDateTime.class), messageId, tableId, userId);
            authentication.requireUnexpired(jwt);
            if (times.isEmpty()) return new Created(Outcome.NOT_FOUND, null);
            createdAt = times.getFirst();
            outcome = Outcome.REPLAYED;
        } else {
            messageId = UUID.randomUUID();
            createdAt = jdbc.queryForObject("""
                    INSERT INTO chat_messages (id, table_id, kind, author_id, text, poll_options)
                    VALUES (?, ?, 'poll', ?, ?, ?) RETURNING created_at
                    """, OffsetDateTime.class, messageId, tableId, userId, question,
                    options.toArray(String[]::new));
            if (createdAt == null) throw new IllegalStateException("Created poll has no timestamp");
            idempotency.saveResponseReference(userId, key, CREATE, messageId);
            outbox.append(OutboxEvent.CHAT_MESSAGE_CREATED, tableId,
                    Map.of("tableId", tableId, "messageId", messageId, "kind", "poll"));
            outcome = Outcome.CREATED;
        }
        authentication.requireUnexpired(jwt);
        return new Created(outcome, new ChatWindowService.Message(messageId, tableId, "poll", createdAt, userId,
                question, null, null, null, null, null,
                new ChatWindowService.Poll(List.copyOf(options), List.of()), List.of()));
    }

    /** Sets (option 0–5) or removes (null) the viewer's vote on a poll at one of their tables. */
    @Transactional
    public Outcome vote(Jwt jwt, UUID messageId, Integer option, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<UUID> hints = jdbc.query("SELECT table_id FROM chat_messages WHERE id = ? AND kind = 'poll'",
                (rs, row) -> rs.getObject("table_id", UUID.class), messageId);
        if (hints.isEmpty()) return Outcome.NOT_FOUND;
        UUID tableId = hints.getFirst();
        List<UUID> tables = jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId);
        List<Integer> optionCounts = tables.isEmpty() ? List.of() : jdbc.query(("""
                SELECT cardinality(m.poll_options) FROM chat_messages m
                JOIN table_members member ON member.table_id = m.table_id AND member.user_id = ?
                JOIN users viewer ON viewer.id = member.user_id
                LEFT JOIN users author ON author.id = m.author_id
                WHERE m.id = ? AND m.table_id = ? AND %s FOR SHARE OF m
                """).formatted(ChatVisibility.VISIBLE), (rs, row) -> rs.getInt(1), userId, messageId, tableId);
        authentication.requireUnexpired(jwt);
        if (optionCounts.isEmpty()) return Outcome.NOT_FOUND;
        if (option != null && option >= optionCounts.getFirst()) return Outcome.BAD_OPTION;
        String operation = option == null ? "chat.poll.unvote.v1" : "chat.poll.vote.v1";
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation,
                option == null ? messageId.toString() : messageId + "|" + option);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.UPDATED;
        int changed = option == null
                ? jdbc.update("DELETE FROM poll_votes WHERE message_id = ? AND user_id = ?", messageId, userId)
                : jdbc.update("""
                        INSERT INTO poll_votes (message_id, user_id, option_index) VALUES (?, ?, ?)
                        ON CONFLICT (message_id, user_id) DO UPDATE
                            SET option_index = EXCLUDED.option_index, voted_at = now()
                        WHERE poll_votes.option_index <> EXCLUDED.option_index
                        """, messageId, userId, option);
        if (changed != 0) {
            outbox.append(OutboxEvent.CHAT_POLL_VOTED, tableId, Map.of("tableId", tableId, "messageId", messageId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}

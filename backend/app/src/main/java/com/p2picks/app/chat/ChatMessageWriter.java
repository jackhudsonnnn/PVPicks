package com.p2picks.app.chat;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How other modules add messages to a table's chat. Each write also records
 * {@link OutboxEvent#CHAT_MESSAGE_CREATED} and must run inside the caller's transaction.
 * Member-authored text and GIF messages are written by {@link ChatSendService}.
 */
@Component
public class ChatMessageWriter {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    ChatMessageWriter(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** A neutral system notice, such as "Table details changed". */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID system(UUID tableId, String text) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                messageId, tableId, text);
        created(tableId, messageId, "system");
        return messageId;
    }

    /**
     * A system notice about one user. Reads show the subject's current username, or
     * {@code Deleted user} after deletion; {@code text} is the stored fallback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID attributedSystem(UUID tableId, String text, String systemEvent, UUID subjectId) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', ?, ?, ?)
                """, messageId, tableId, text, systemEvent, subjectId);
        created(tableId, messageId, "system");
        return messageId;
    }

    /** The settlement card shown in chat after a host settles table points. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID settlementCard(UUID tableId, UUID settlementId) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                messageId, tableId, settlementId);
        created(tableId, messageId, "settlement");
        return messageId;
    }

    private void created(UUID tableId, UUID messageId, String kind) {
        outbox.append(OutboxEvent.CHAT_MESSAGE_CREATED, tableId,
                Map.of("tableId", tableId, "messageId", messageId, "kind", kind));
    }
}

package com.p2picks.app.chat;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SystemChatWriter {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    SystemChatWriter(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID tableId, String text) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                messageId, tableId, text);
        outbox.append(OutboxEvent.CHAT_MESSAGE_CREATED, tableId,
                Map.of("tableId", tableId, "messageId", messageId, "kind", "system"));
    }
}

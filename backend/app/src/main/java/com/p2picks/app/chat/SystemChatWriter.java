package com.p2picks.app.chat;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SystemChatWriter {
    private final JdbcTemplate jdbc;

    SystemChatWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID tableId, String text) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                messageId, tableId, text);
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'chat', ?, 'chat.message.created.v1',
                        jsonb_build_object('tableId', ?::text, 'messageId', ?::text, 'kind', 'system'))
                """, UUID.randomUUID(), tableId.toString(), tableId.toString(), messageId.toString());
    }
}

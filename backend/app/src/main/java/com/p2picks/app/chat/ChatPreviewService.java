package com.p2picks.app.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * The latest message the viewer can see at each of their current tables, for the table cards on Home. Uses the same
 * visibility and text rules as chat windows, so a blocked author's message never shows up as a preview.
 */
@Service
class ChatPreviewService {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Message(UUID id, String kind, OffsetDateTime createdAt, UUID authorId, String text) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Preview(UUID tableId, Message message) {}

    private record Row(UUID tableId, Message message) {}

    private final JdbcTemplate jdbc;

    ChatPreviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty when the session is not valid; otherwise one preview per current table, with a null message if none. */
    Optional<List<Preview>> latest(Jwt jwt) {
        List<Row> rows = jdbc.query("""
                WITH viewer AS MATERIALIZED (
                    SELECT u.id FROM auth_sessions session JOIN users u ON u.id = session.user_id
                    WHERE session.id = ? AND u.id = ? AND session.revoked_at IS NULL
                      AND session.expires_at > now() AND u.status = 'ACTIVE'
                )
                SELECT member_table.id AS table_id, latest.id, latest.kind, latest.created_at, latest.author_id,
                       latest.text
                FROM viewer
                LEFT JOIN LATERAL (
                    SELECT t.id FROM table_members member
                    JOIN tables t ON t.id = member.table_id AND t.archived_at IS NULL
                    WHERE member.user_id = viewer.id
                ) member_table ON true
                LEFT JOIN LATERAL (
                    SELECT m.id, m.kind, m.created_at, m.author_id,
                           CASE WHEN m.kind IN ('text', 'system') THEN %s END AS text
                    FROM chat_messages m
                    LEFT JOIN users author ON author.id = m.author_id
                    LEFT JOIN users subject ON subject.id = m.system_subject_id
                    WHERE m.table_id = member_table.id AND %s
                    ORDER BY m.message_order DESC
                    LIMIT 1
                ) latest ON true
                ORDER BY member_table.id
                """.formatted(ChatVisibility.TEXT, ChatVisibility.VISIBLE), (rs, row) -> {
                    UUID tableId = rs.getObject("table_id", UUID.class);
                    UUID id = rs.getObject("id", UUID.class);
                    return new Row(tableId, id == null ? null : new Message(id, rs.getString("kind"),
                            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("author_id", UUID.class),
                            rs.getString("text")));
                }, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (rows.isEmpty()) return Optional.empty();
        List<Preview> previews = new ArrayList<>();
        for (Row row : rows) {
            if (row.tableId() != null) previews.add(new Preview(row.tableId(), row.message()));
        }
        return Optional.of(List.copyOf(previews));
    }
}

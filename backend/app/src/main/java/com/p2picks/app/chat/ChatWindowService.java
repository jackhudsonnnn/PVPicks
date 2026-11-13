package com.p2picks.app.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class ChatWindowService {
    public enum Direction { LATEST, BEFORE, AFTER, AROUND }

    public record Query(Direction direction, Long position, UUID anchor) {}
    public record Result(boolean authenticated, boolean accessible, boolean anchorFound, Window window) {}
    public record Window(List<Message> items, String previousCursor, String nextCursor) {}
    record Reaction(UUID userId, String emoji) {}
    record SettlementLine(UUID payerId, UUID payeeId, long amountCents) {}
    public record SettlementSummary(UUID hostId, List<SettlementLine> lines) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(UUID id, UUID tableId, String kind, OffsetDateTime createdAt, UUID authorId,
            String text, String gifId, UUID betId, UUID settlementId,
            SettlementSummary settlementSummary, List<Reaction> reactions) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ChatCursor cursors;

    ChatWindowService(JdbcTemplate jdbc, ObjectMapper json, ChatCursor cursors) {
        this.jdbc = jdbc;
        this.json = json;
        this.cursors = cursors;
    }

    public Result read(Jwt jwt, UUID tableId, Query query) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String selection = switch (query.direction()) {
            case LATEST -> "SELECT * FROM visible ORDER BY message_order DESC LIMIT 30";
            case BEFORE -> "SELECT * FROM visible WHERE message_order < ? ORDER BY message_order DESC LIMIT 25";
            case AFTER -> "SELECT * FROM visible WHERE message_order > ? ORDER BY message_order LIMIT 25";
            case AROUND -> """
                    SELECT * FROM (
                        (SELECT * FROM visible WHERE message_order < (SELECT message_order FROM anchor)
                         ORDER BY message_order DESC LIMIT 5)
                        UNION ALL
                        (SELECT * FROM visible WHERE message_order >= (SELECT message_order FROM anchor)
                         ORDER BY message_order LIMIT 25)
                    ) around_window
                    """;
        };
        String sql = ("""
                WITH viewer AS MATERIALIZED (
                    SELECT u.id FROM auth_sessions session JOIN users u ON u.id = session.user_id
                    WHERE session.id = ? AND u.id = ? AND session.revoked_at IS NULL
                      AND session.expires_at > now() AND u.status = 'ACTIVE'
                ), access AS MATERIALIZED (
                    SELECT t.id FROM tables t CROSS JOIN viewer WHERE t.id = ? AND (
                        (t.archived_at IS NULL AND EXISTS (
                            SELECT 1 FROM table_members member
                            WHERE member.table_id = t.id AND member.user_id = viewer.id))
                        OR (t.archived_at IS NOT NULL AND t.archived_at + interval '2160 hours' > now()
                            AND EXISTS (SELECT 1 FROM table_archive_members member
                                WHERE member.table_id = t.id AND member.user_id = viewer.id))
                    )
                ), visible AS NOT MATERIALIZED (
                    SELECT m.* FROM chat_messages m JOIN access ON access.id = m.table_id
                    CROSS JOIN viewer LEFT JOIN users author ON author.id = m.author_id
                    WHERE %s
                ), anchor AS MATERIALIZED (
                    SELECT message_order FROM visible WHERE id = ?::uuid
                ), chosen AS MATERIALIZED (
                    %s
                ), bounds AS (
                    SELECT min(message_order) AS first FROM chosen
                )
                SELECT viewer.id AS viewer_id, access.id AS access_id, anchor.message_order AS anchor_order,
                       m.id, m.table_id, m.kind, m.created_at, m.author_id,
                       CASE m.system_event
                           WHEN 'host_promoted' THEN COALESCE(subject.username::text, 'Deleted user') || ' is now a host'
                           WHEN 'host_demoted' THEN COALESCE(subject.username::text, 'Deleted user') || ' is no longer a host'
                           ELSE m.text
                       END AS text, m.gif_id, m.bet_id,
                       m.settlement_id, m.message_order, s.host_id,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object('userId', r.user_id, 'emoji', r.emoji)
                                                  ORDER BY r.user_id)
                                 FROM reactions r JOIN users reactor ON reactor.id = r.user_id
                                 WHERE r.message_id = m.id AND reactor.status <> 'DELETED'), '[]'::jsonb)::text AS reactions,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object('payerId', line.payer_id,
                                     'payeeId', line.payee_id, 'amountCents', line.amount_cents)
                                     ORDER BY line.created_at, line.id)
                                 FROM settlement_lines line WHERE line.settlement_id = m.settlement_id),
                                '[]'::jsonb)::text AS lines,
                       EXISTS (SELECT 1 FROM visible older, bounds b WHERE older.message_order < b.first) AS has_older
                FROM viewer LEFT JOIN access ON true LEFT JOIN anchor ON true
                LEFT JOIN chosen m ON true
                LEFT JOIN users subject ON subject.id = m.system_subject_id
                LEFT JOIN settlements s ON s.id = m.settlement_id AND s.table_id = m.table_id
                ORDER BY m.message_order
                """).formatted(ChatVisibility.VISIBLE, selection);
        List<Object> parameters = new ArrayList<>(List.of(UUID.fromString(jwt.getId()), userId, tableId));
        parameters.add(query.anchor());
        if (query.direction() == Direction.BEFORE || query.direction() == Direction.AFTER) {
            parameters.add(query.position());
        }
        return jdbc.query(sql, rs -> {
            return extract(rs, userId, tableId, query);
        }, parameters.toArray());
    }

    private Result extract(ResultSet rs, UUID userId, UUID tableId, Query query) throws SQLException {
        if (!rs.next()) return new Result(false, false, false, null);
        if (rs.getObject("access_id", UUID.class) == null) return new Result(true, false, false, null);
        if (query.direction() == Direction.AROUND && rs.getObject("anchor_order") == null) {
            return new Result(true, true, false, null);
        }
        boolean hasOlder = rs.getBoolean("has_older");
        List<Message> messages = new ArrayList<>();
        long first = 0;
        long last = 0;
        do {
            UUID id = rs.getObject("id", UUID.class);
            if (id == null) continue;
            long order = rs.getLong("message_order");
            if (messages.isEmpty()) first = order;
            last = order;
            UUID host = rs.getObject("host_id", UUID.class);
            SettlementSummary summary = host == null ? null : new SettlementSummary(host, parseLines(rs.getString("lines")));
            messages.add(new Message(id, tableId, rs.getString("kind"),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("author_id", UUID.class),
                    rs.getString("text"), rs.getString("gif_id"), rs.getObject("bet_id", UUID.class),
                    rs.getObject("settlement_id", UUID.class), summary, parseReactions(rs.getString("reactions"))));
        } while (rs.next());
        return new Result(true, true, true, new Window(List.copyOf(messages),
                hasOlder ? cursors.encode(userId, tableId, first) : null,
                messages.isEmpty() ? null : cursors.encode(userId, tableId, last)));
    }

    private List<Reaction> parseReactions(String value) throws SQLException {
        try {
            return json.readValue(value, new TypeReference<List<Reaction>>() {});
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid reaction projection", exception);
        }
    }

    private List<SettlementLine> parseLines(String value) throws SQLException {
        try {
            return json.readValue(value, new TypeReference<List<SettlementLine>>() {});
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid settlement projection", exception);
        }
    }
}

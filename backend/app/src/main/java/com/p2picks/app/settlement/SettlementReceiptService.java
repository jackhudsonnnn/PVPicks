package com.p2picks.app.settlement;

import com.p2picks.app.common.PageCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Receipts: one table's points settlement between two people, unpaid until a settle closes it (paid or forgiven).
 * Settles change them ({@link SettleService}); this reads them.
 */
@Service
public class SettlementReceiptService {
    /**
     * A receipt. {@code settleId} is the settle that closed it (null while unpaid); a paid receipt whose sent settle
     * is still inside its reject window can reopen.
     */
    public record Line(UUID id, UUID settlementId, UUID tableId, UUID payerId, UUID payeeId, long amountCents,
            String status, int revision, UUID settleId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        boolean open() {
            return status.equals("unpaid");
        }
    }

    /** Closed receipts page this many at a time; open ones always come in full (you need them all to settle). */
    static final int PAGE = 20;

    record History(boolean authenticated, boolean player, List<Line> open, List<Line> closed, String nextCursor) {}

    private final JdbcTemplate jdbc;

    SettlementReceiptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Your receipts with one person (optionally at one table): every open one, newest first, and the closed ones
     * (paid or forgiven) newest first, {@link #PAGE} at a time. A cursor continues the closed list and leaves the open
     * one empty. Throws IllegalArgumentException (or DateTimeParseException) on a bad cursor.
     */
    History history(Jwt jwt, UUID otherId, UUID tableId, String cursor) {
        OffsetDateTime before = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> parts = PageCursor.decode(cursor, 2);
            before = OffsetDateTime.parse(parts.get(0));
            beforeId = UUID.fromString(parts.get(1));
        }
        UUID viewer = UUID.fromString(jwt.getSubject());
        List<String> roles = jdbc.queryForList("""
                SELECT u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                  AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                """, String.class, viewer, UUID.fromString(jwt.getId()));
        if (roles.isEmpty()) return new History(false, false, List.of(), List.of(), null);
        if (!roles.getFirst().equals("PLAYER")) return new History(true, false, List.of(), List.of(), null);
        String pair = """
                SELECT l.* FROM settlement_lines l
                WHERE ((l.payer_id = ? AND l.payee_id = ?) OR (l.payee_id = ? AND l.payer_id = ?))
                  AND (?::uuid IS NULL OR l.table_id = ?)
                """;
        List<Line> open = cursor != null ? List.of() : jdbc.query(pair + """
                  AND l.status = 'unpaid'
                ORDER BY l.created_at DESC, l.id DESC
                """, (rs, row) -> line(rs), viewer, otherId, viewer, otherId, tableId, tableId);
        List<Line> closed = jdbc.query(pair + """
                  AND l.status <> 'unpaid'
                  AND (?::timestamptz IS NULL OR (l.created_at, l.id) < (?::timestamptz, ?::uuid))
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ?
                """, (rs, row) -> line(rs), viewer, otherId, viewer, otherId, tableId, tableId, before, before,
                beforeId, PAGE + 1);
        String next = null;
        if (closed.size() > PAGE) {
            closed = closed.subList(0, PAGE);
            Line last = closed.getLast();
            next = PageCursor.encode(last.createdAt(), last.id());
        }
        return new History(true, true, List.copyOf(open), List.copyOf(closed), next);
    }

    static Line line(ResultSet rs) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class),
                rs.getObject("table_id", UUID.class), rs.getObject("payer_id", UUID.class),
                rs.getObject("payee_id", UUID.class), rs.getLong("amount_cents"), rs.getString("status"),
                rs.getInt("revision"), rs.getObject("settle_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}

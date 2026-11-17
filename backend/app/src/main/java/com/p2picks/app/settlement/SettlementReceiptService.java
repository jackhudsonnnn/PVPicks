package com.p2picks.app.settlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    record History(boolean authenticated, boolean player, List<Line> lines) {}

    private final JdbcTemplate jdbc;

    SettlementReceiptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Your receipts with one person (optionally at one table): open first, then newest first. */
    History history(Jwt jwt, UUID otherId, UUID tableId) {
        return jdbc.query("""
                WITH viewer AS (
                    SELECT u.id, u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                    WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                      AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                )
                SELECT viewer.role AS viewer_role, l.*
                FROM viewer LEFT JOIN settlement_lines l ON
                    ((l.payer_id = viewer.id AND l.payee_id = ?)
                      OR (l.payee_id = viewer.id AND l.payer_id = ?))
                    AND (?::uuid IS NULL OR l.table_id = ?)
                    AND viewer.role = 'PLAYER'
                ORDER BY CASE WHEN l.status = 'unpaid' THEN 0 ELSE 1 END, l.created_at DESC, l.id DESC
                """, rs -> {
                    if (!rs.next()) return new History(false, false, List.of());
                    boolean player = "PLAYER".equals(rs.getString("viewer_role"));
                    List<Line> lines = new ArrayList<>();
                    do {
                        if (rs.getObject("id", UUID.class) != null) lines.add(line(rs));
                    } while (rs.next());
                    return new History(true, player, List.copyOf(lines));
                }, UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getId()), otherId, otherId, tableId,
                tableId);
    }

    static Line line(ResultSet rs) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class),
                rs.getObject("table_id", UUID.class), rs.getObject("payer_id", UUID.class),
                rs.getObject("payee_id", UUID.class), rs.getLong("amount_cents"), rs.getString("status"),
                rs.getInt("revision"), rs.getObject("settle_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}

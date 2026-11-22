package com.p2picks.app.settlement;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Settle-ups by person: everyone you have open receipts with, or a sent settle still inside its reject window.
 * Each group carries its open receipts and net, any balance change you haven't reviewed, and those in-window
 * settles (the receiver can still reject them).
 */
@Service
class SettlementGroupService {
    record GroupRead(UUID otherUserId, BigInteger netCents, List<Line> lines, UUID pendingReviewRevision,
            List<SettleService.SettleView> pendingSettles) {}
    record Groups(boolean authenticated, boolean player, List<GroupRead> groups) {}

    private final JdbcTemplate jdbc;
    private final SettleService settles;

    SettlementGroupService(JdbcTemplate jdbc, SettleService settles) {
        this.jdbc = jdbc;
        this.settles = settles;
    }

    Groups read(Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getSubject());
        List<String> roles = jdbc.queryForList("""
                SELECT u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                  AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                """, String.class, actor, UUID.fromString(jwt.getId()));
        if (roles.isEmpty()) return new Groups(false, false, List.of());
        if (!roles.getFirst().equals("PLAYER")) return new Groups(true, false, List.of());
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);

        Map<UUID, List<Line>> open = new LinkedHashMap<>();
        jdbc.query("""
                SELECT * FROM settlement_lines WHERE (payer_id = ? OR payee_id = ?) AND status = 'unpaid'
                ORDER BY created_at DESC, id DESC
                """, rs -> {
                    Line line = SettlementReceiptService.line(rs);
                    open.computeIfAbsent(other(line, actor), ignored -> new ArrayList<>()).add(line);
                }, actor, actor);
        Map<UUID, List<UUID>> windows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, CASE WHEN sender_id = ? THEN other_id ELSE sender_id END AS other FROM settles
                WHERE (sender_id = ? OR other_id = ?) AND kind = 'sent' AND rejected_at IS NULL
                  AND reject_by > clock_timestamp()
                ORDER BY created_at DESC, id DESC
                """, rs -> {
                    windows.computeIfAbsent(rs.getObject("other", UUID.class), ignored -> new ArrayList<>())
                            .add(rs.getObject("id", UUID.class));
                }, actor, actor, actor);
        Map<UUID, UUID> reviews = new LinkedHashMap<>();
        jdbc.query("""
                SELECT other_user_id, revision FROM settlement_pair_reviews
                WHERE user_id = ? AND reviewed_at IS NULL
                """, rs -> {
                    reviews.put(rs.getObject("other_user_id", UUID.class), rs.getObject("revision", UUID.class));
                }, actor);

        List<UUID> people = new ArrayList<>(open.keySet());
        windows.keySet().stream().filter(other -> !open.containsKey(other)).forEach(people::add);
        List<GroupRead> groups = new ArrayList<>();
        for (UUID other : people) {
            List<Line> lines = List.copyOf(open.getOrDefault(other, List.of()));
            BigInteger net = BigInteger.ZERO;
            for (Line line : lines) {
                BigInteger amount = BigInteger.valueOf(line.amountCents());
                net = net.add(line.payeeId().equals(actor) ? amount : amount.negate());
            }
            groups.add(new GroupRead(other, net, lines, lines.isEmpty() ? null : reviews.get(other),
                    settles.views(windows.getOrDefault(other, List.of()), actor, now)));
        }
        // People you're square with (0.00) go last; otherwise newest activity first, as read.
        groups.sort(Comparator.comparing(group -> group.netCents().signum() == 0));
        return new Groups(true, true, List.copyOf(groups));
    }

    private static UUID other(Line line, UUID actor) {
        return line.payerId().equals(actor) ? line.payeeId() : line.payerId();
    }
}

package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.shared.kernel.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Each current member's points at a table, for the seats, the Peers tab and host settle (domain rules, "Balances").
 *
 * <p>{@code balanceCents} is the member's balance: their ledger deltas from resolved bets whose points are not
 * settled yet, minus what they have in play (wagers in locked, Pending pots and each Jackblack game's worst case),
 * so it assumes they lose everything still in play. {@code unsettledCents} is only the resolved part, what the next
 * host settle moves; pending bets never enter a settle. Members can already read every input here (resolved deltas
 * and post-lock picks are part of each bet's projection), so this exposes nothing new; Active picks are never
 * counted.
 */
@RestController
class TableBalanceController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    TableBalanceController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The viewer's session and their access to the table; {@code ?} order: session, user, table. */
    private static final String ACCESS = """
            WITH viewer AS (
                SELECT u.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL
                  AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
            ), access AS (
                SELECT EXISTS (
                    SELECT 1 FROM table_members m JOIN tables t ON t.id = m.table_id
                    WHERE m.user_id = viewer.id AND t.id = ? AND t.archived_at IS NULL
                ) AS ok FROM viewer
            )
            """;

    /** A member's unsettled results and points in play; {@code ?} order: table. Reads {@code m} (table_members). */
    private static final String MEMBER_POINTS = """
            COALESCE((SELECT sum(le.delta_cents) FROM ledger_entries le
                      JOIN bets b ON b.id = le.bet_id
                      WHERE le.table_id = m.table_id AND le.user_id = m.user_id
                        AND b.status = 'SETTLED' AND b.points_settled_at IS NULL), 0) AS unsettled,
            COALESCE((SELECT sum(b.wager_cents) FROM bets b
                      JOIN picks p ON p.bet_id = b.id
                      WHERE b.table_id = m.table_id AND p.user_id = m.user_id
                        AND b.status = 'PENDING' AND NOT b.is_4fun), 0)
            -- Card games in play (Jackblack, Hold'em): each player's worst case (domain rules).
            + COALESCE((SELECT sum(j.risk_cents)
                      FROM bets b JOIN card_players j ON j.bet_id = b.id
                        AND j.user_id = m.user_id
                      WHERE b.table_id = m.table_id
                        AND b.status = 'PENDING' AND NOT b.is_4fun), 0) AS staked
            """;

    @GetMapping("/tables/{tableId}/balances")
    ResponseEntity<?> balances(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        Result result = jdbc.query(ACCESS + """
                SELECT access.ok, member.user_id, member.unsettled, member.staked
                FROM access
                LEFT JOIN LATERAL (
                    SELECT m.user_id,
                """ + MEMBER_POINTS + """
                    FROM table_members m
                    WHERE m.table_id = ? AND access.ok
                    ORDER BY m.user_id
                ) member ON true
                """, rs -> {
            if (!rs.next()) return new Result(false, false, List.of());
            boolean ok = rs.getBoolean("ok");
            List<Row> rows = new ArrayList<>();
            do {
                String id = rs.getString("user_id");
                if (id != null) rows.add(new Row(UUID.fromString(id), rs.getBigDecimal("unsettled"),
                        rs.getBigDecimal("staked")));
            } while (rs.next());
            return new Result(true, ok, rows);
        }, sessionId, userId, tableId, tableId);
        if (result == null || !result.authenticated()) return unauthorized();
        if (!result.accessible()) return notFound();
        List<Map<String, Object>> body = new ArrayList<>();
        try {
            for (Row row : result.rows()) body.add(view(row));
        } catch (ArithmeticException outOfRange) {
            return outOfRange();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * One member's balance with each ticket they have in play, for the Peers tab's what-if view: what the ticket
     * adds to the balance if it loses ({@code loseCents}, already counted in the balance) and if it wins
     * ({@code winCents}). A pot's win is the payout if the member's pick wins, from the same calculator resolution
     * uses; a Jackblack game's win is its best case, the mirror of the worst case (every round sitting at the most a
     * seat can win, every round dealing at the most each seat can lose); a Hold'em game's is every other player's
     * buy-ins. The balance here is built from the same
     * tickets (unsettled results plus every {@code loseCents}), so the what-if always adds up.
     */
    @GetMapping("/tables/{tableId}/members/{memberId}/balance")
    ResponseEntity<?> memberBalance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
                                    @PathVariable UUID memberId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        Result result = jdbc.query(ACCESS + """
                SELECT access.ok, member.user_id, member.unsettled, member.staked
                FROM access
                LEFT JOIN LATERAL (
                    SELECT m.user_id,
                """ + MEMBER_POINTS + """
                    FROM table_members m
                    WHERE m.table_id = ? AND m.user_id = ? AND access.ok
                ) member ON true
                """, rs -> {
            if (!rs.next()) return new Result(false, false, List.of());
            boolean ok = rs.getBoolean("ok");
            String id = rs.getString("user_id");
            List<Row> rows = id == null ? List.of() : List.of(new Row(UUID.fromString(id),
                    rs.getBigDecimal("unsettled"), rs.getBigDecimal("staked")));
            return new Result(true, ok, rows);
        }, sessionId, userId, tableId, tableId, memberId);
        if (result == null || !result.authenticated()) return unauthorized();
        if (!result.accessible() || result.rows().isEmpty()) return notFound();
        Row member = result.rows().getFirst();
        List<Map<String, Object>> tickets = new ArrayList<>();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            long balance = member.unsettled().longValueExact();
            for (Ticket ticket : tickets(tableId, memberId)) {
                balance = Math.addExact(balance, ticket.loseCents());
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("betId", ticket.betId());
                view.put("mode", ticket.mode());
                view.put("question", ticket.question());
                if (ticket.choice() != null) view.put("choice", ticket.choice());
                view.put("loseCents", ticket.loseCents());
                view.put("winCents", ticket.winCents());
                tickets.add(view);
            }
            body.put("userId", member.userId());
            body.put("balanceCents", balance);
            body.put("unsettledCents", member.unsettled().longValueExact());
        } catch (ArithmeticException outOfRange) {
            return outOfRange();
        }
        body.put("tickets", tickets);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The member's tickets in play at the table, oldest lock first: the same set the balance subtracts. */
    private List<Ticket> tickets(UUID tableId, UUID memberId) {
        List<Ticket> tickets = new ArrayList<>();
        jdbc.query("""
                SELECT b.id, b.mode, b.question, b.choices::text AS choices, b.wager_cents, p.choice,
                       j.risk_cents,
                       (SELECT COALESCE(sum(o.risk_cents), 0) FROM card_players o
                        WHERE o.bet_id = b.id AND o.user_id <> j.user_id) AS others_risk,
                       (SELECT COALESCE(jsonb_agg(jsonb_build_array(bp.user_id, pp.choice)
                                                  ORDER BY bp.participation_order), '[]'::jsonb)::text
                        FROM bet_participants bp
                        LEFT JOIN picks pp ON pp.bet_id = bp.bet_id AND pp.user_id = bp.user_id
                        WHERE bp.bet_id = b.id) AS participants
                FROM bets b
                LEFT JOIN picks p ON p.bet_id = b.id AND p.user_id = ?
                LEFT JOIN card_players j ON j.bet_id = b.id AND j.user_id = ?
                WHERE b.table_id = ? AND b.status = 'PENDING' AND NOT b.is_4fun
                  AND (p.user_id IS NOT NULL OR j.user_id IS NOT NULL)
                ORDER BY b.locked_at NULLS LAST, b.id
                """, rs -> {
            UUID betId = rs.getObject("id", UUID.class);
            String mode = rs.getString("mode");
            String question = rs.getString("question");
            String choice = rs.getString("choice");
            long lose = 0;
            long win = 0;
            if (choice != null) {
                long wager = rs.getLong("wager_cents");
                lose = Math.negateExact(wager);
                win = payoutIfWins(betId, memberId, choice, wager, rs.getString("choices"),
                        rs.getString("participants"));
            }
            Long risk = rs.getObject("risk_cents", Long.class);
            if (risk != null) {
                lose = Math.subtractExact(lose, risk);
                // Jackblack's best case mirrors its worst case; at Hold'em you can at best win every other
                // player's buy-ins.
                win = Math.addExact(win, mode.equals("holdem") ? rs.getLong("others_risk") : risk);
            }
            tickets.add(new Ticket(betId, mode, question, choice, lose, win));
        }, memberId, memberId, tableId);
        return tickets;
    }

    /** The member's ledger delta if their pick wins; a wash (which a locked pot shouldn't reach) moves nothing. */
    private long payoutIfWins(UUID betId, UUID memberId, String choice, long wager, String choices,
                              String participants) {
        List<String> available = new ArrayList<>();
        List<PayoutCalculator.Participant> players = new ArrayList<>();
        try {
            for (JsonNode option : json.readTree(choices)) available.add(option.textValue());
            for (JsonNode pair : json.readTree(participants)) {
                players.add(new PayoutCalculator.Participant(UUID.fromString(pair.get(0).asText()),
                        pair.get(1).isNull() ? null : pair.get(1).asText()));
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Stored bet is not valid JSON", invalid);
        }
        PayoutCalculator.Result result = PayoutCalculator.settle(betId, players, new Money(wager), false,
                available, choice);
        if (result instanceof PayoutCalculator.Result.Resolved resolved) {
            Money delta = resolved.deltas().get(memberId);
            return delta == null ? 0 : delta.cents();
        }
        return 0;
    }

    private static Map<String, Object> view(Row row) {
        Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("userId", row.userId());
        balance.put("balanceCents", row.unsettled().subtract(row.staked()).longValueExact());
        balance.put("unsettledCents", row.unsettled().longValueExact());
        return balance;
    }

    private static ResponseEntity<?> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<?> notFound() {
        return error(404, "not_found", "Table not found.");
    }

    private static ResponseEntity<?> outOfRange() {
        return error(409, "balance_out_of_range", "A table balance is outside the supported range.");
    }

    private record Row(UUID userId, BigDecimal unsettled, BigDecimal staked) {}

    private record Ticket(UUID betId, String mode, String question, String choice, long loseCents, long winCents) {}

    private record Result(boolean authenticated, boolean accessible, List<Row> rows) {}
}

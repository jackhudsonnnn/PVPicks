package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.bets.U2PickSettler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class U2PickResolutionService {
    enum Outcome { RESOLVED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    static class Closed extends RuntimeException {}
    static class InvalidChoice extends RuntimeException {}
    static class CopyPropagationUnavailable extends IllegalStateException {
        CopyPropagationUnavailable() {
            super("Quick Pick result propagation is not implemented");
        }
    }
    static class MembersChanged extends RuntimeException {
        MembersChanged() {
            super("Affected users changed while acquiring locks; retry the transaction");
        }
    }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final BetResolutionWriter writer;
    private final ObjectMapper json;

    U2PickResolutionService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, BetResolutionWriter writer, ObjectMapper json) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.writer = writer;
        this.json = json;
    }

    @Transactional
    public Outcome resolve(Jwt jwt, UUID betId, String winningChoice, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (winningChoice != null && !PostgresText.valid(winningChoice)) throw new InvalidChoice();
        UUID actor = UUID.fromString(jwt.getSubject());
        List<UUID> tableHints = jdbc.query("SELECT table_id FROM bets WHERE id = ?",
                (rs, row) -> rs.getObject("table_id", UUID.class), betId);
        List<UUID> targets = tableHints.isEmpty() ? List.of() : jdbc.query("""
                SELECT user_id FROM table_members WHERE table_id = ?
                UNION SELECT user_id FROM bet_participants WHERE bet_id = ?
                UNION SELECT proposer_id FROM bets WHERE id = ?
                UNION SELECT remainder_host_id FROM bets WHERE id = ? AND remainder_host_id IS NOT NULL
                """, (rs, row) -> rs.getObject(1, UUID.class), tableHints.getFirst(), betId, betId, betId);
        Map<UUID, String> lockedUsers = authentication.lockActiveWithTargets(jwt, targets);
        if (lockedUsers == null) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return Outcome.FORBIDDEN;
        }
        if (tableHints.isEmpty()) return Outcome.NOT_FOUND;
        UUID tableId = tableHints.getFirst();
        if (jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId).isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Member> members = jdbc.query("""
                SELECT m.user_id, m.role, u.role AS account_role, u.status
                FROM table_members m JOIN users u ON u.id = m.user_id WHERE m.table_id = ?
                """, (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role"),
                        rs.getString("account_role"), rs.getString("status")), tableId);
        if (members.stream().noneMatch(member -> member.id().equals(actor))) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Bet> bets = jdbc.query("""
                SELECT proposer_id, mode, source_bet_id, status, choices::text, wager_cents, is_4fun,
                       settler_rule, remainder_rule, remainder_host_id
                FROM bets WHERE id = ? AND table_id = ? FOR UPDATE
                """, (rs, row) -> new Bet(rs.getObject("proposer_id", UUID.class), rs.getString("mode"),
                        rs.getObject("source_bet_id", UUID.class), rs.getString("status"),
                        rs.getString("choices"), rs.getLong("wager_cents"), rs.getBoolean("is_4fun"),
                        rs.getString("settler_rule"), rs.getString("remainder_rule"),
                        rs.getObject("remainder_host_id", UUID.class)), betId, tableId);
        if (bets.isEmpty()) throw new IllegalStateException("Bet disappeared during resolution");
        Bet bet = bets.getFirst();
        if (!bet.mode().equals("u2pick") || bet.sourceBetId() != null) return Outcome.FORBIDDEN;
        if (bet.status().equals("ACTIVE")) throw new Closed();
        List<PayoutCalculator.Participant> participants = jdbc.query("""
                SELECT bp.user_id, p.choice FROM bet_participants bp
                LEFT JOIN picks p ON p.bet_id = bp.bet_id AND p.user_id = bp.user_id
                WHERE bp.bet_id = ? ORDER BY bp.participation_order
                """, (rs, row) -> new PayoutCalculator.Participant(rs.getObject("user_id", UUID.class),
                        rs.getString("choice")), betId);
        if (members.stream().anyMatch(member -> !lockedUsers.containsKey(member.id()))
                || participants.stream().anyMatch(participant -> !lockedUsers.containsKey(participant.userId()))
                || !lockedUsers.containsKey(bet.proposerId())
                || (bet.remainderHostId() != null && !lockedUsers.containsKey(bet.remainderHostId()))) {
            throw new MembersChanged();
        }
        Set<UUID> eligible = new HashSet<>();
        Set<UUID> hosts = new HashSet<>();
        for (Member member : members) {
            if (member.status().equals("ACTIVE") && member.accountRole().equals("PLAYER")) {
                eligible.add(member.id());
                if (member.role().equals("HOST")) hosts.add(member.id());
            }
        }
        Set<UUID> inPot = new HashSet<>();
        for (PayoutCalculator.Participant participant : participants) {
            if (participant.choice() != null) inPot.add(participant.userId());
        }
        if (bet.settlerRule() == null) throw new IllegalStateException("U2Pick has no settler rule");
        U2PickSettler.Rule settler = U2PickSettler.Rule.valueOf(bet.settlerRule().toUpperCase(Locale.ROOT));
        if (!U2PickSettler.canSettle(settler, actor, bet.proposerId(), eligible, hosts, inPot)) {
            authentication.requireUnexpired(jwt);
            return Outcome.FORBIDDEN;
        }
        String request = betId + "|" + (winningChoice == null ? "null" : "choice:" + winningChoice);
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, "bet.u2pick.resolve.v1", request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("PENDING")) throw new Closed();
        Boolean hasCopies = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bets WHERE source_bet_id = ?)",
                Boolean.class, betId);
        if (!Boolean.FALSE.equals(hasCopies)) {
            throw new CopyPropagationUnavailable();
        }
        List<String> choices = choices(bet.choices());
        if (winningChoice != null && !choices.contains(winningChoice)) throw new InvalidChoice();
        if (bet.remainderRule() == null) throw new IllegalStateException("Pending bet has no remainder rule");
        PayoutCalculator.RemainderMode mode =
                PayoutCalculator.RemainderMode.valueOf(bet.remainderRule().toUpperCase(Locale.ROOT));
        UUID recipient = switch (mode) {
            case HOST -> bet.remainderHostId();
            case PROPOSER -> bet.proposerId();
            case EARLIEST, RANDOM -> null;
        };
        writer.resolve(new BetResolutionWriter.LockedBet(betId, tableId, bet.wagerCents(), bet.forFun(), choices,
                new PayoutCalculator.Remainder(mode, recipient), participants), winningChoice, actor);
        authentication.requireUnexpired(jwt);
        return Outcome.RESOLVED;
    }

    private List<String> choices(String serialized) {
        try {
            JsonNode array = json.readTree(serialized);
            if (!array.isArray()) throw new IllegalStateException("Bet choices are not an array");
            List<String> values = new ArrayList<>();
            for (JsonNode value : array) {
                if (!value.isTextual()) throw new IllegalStateException("Bet choice is not text");
                values.add(value.textValue());
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot parse bet choices", exception);
        }
    }

    private record Member(UUID id, String role, String accountRole, String status) {}
    private record Bet(UUID proposerId, String mode, UUID sourceBetId, String status, String choices,
                       long wagerCents, boolean forFun, String settlerRule, String remainderRule,
                       UUID remainderHostId) {}
}

package com.p2picks.app.tables;

import com.p2picks.app.chat.ChatMessageWriter;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.TableSettlementGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableMemberRemovalService {
    enum Outcome { REMOVED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    static class SelfRemoval extends RuntimeException {}
    static class TargetHost extends RuntimeException {}
    static class UnsettledBets extends RuntimeException {}
    static class MissingTarget extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final ChatMessageWriter chatMessages;
    private final TableSettlementGuard settlementGuard;
    private final Outbox outbox;

    TableMemberRemovalService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, ChatMessageWriter chatMessages, TableSettlementGuard settlementGuard,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.chatMessages = chatMessages;
        this.settlementGuard = settlementGuard;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome remove(Jwt jwt, UUID tableId, UUID targetId, String key) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        MutationAuthentication.PairStatus pair = authentication.lockActivePair(jwt, targetId);
        if (pair == MutationAuthentication.PairStatus.UNAUTHORIZED) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (pair == MutationAuthentication.PairStatus.TARGET_UNAVAILABLE) return Outcome.NOT_FOUND;
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Member> members = jdbc.query("""
                SELECT user_id, role FROM table_members
                WHERE table_id = ? AND user_id IN (?, ?) ORDER BY user_id FOR UPDATE
                """, (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role")),
                tableId, actorId, targetId);
        Member actor = members.stream().filter(member -> member.userId().equals(actorId)).findFirst().orElse(null);
        authentication.requireUnexpired(jwt);
        if (actor == null) return Outcome.NOT_FOUND;
        if (!actor.role().equals("HOST")) return Outcome.FORBIDDEN;
        if (actorId.equals(targetId)) throw new SelfRemoval();

        IdempotencyGuard.Claim claim = idempotency.claim(actorId, key, "table.member.remove.v1",
                tableId + "|" + targetId);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        Member target = members.stream().filter(member -> member.userId().equals(targetId)).findFirst().orElse(null);
        if (target == null) throw new MissingTarget();
        if (target.role().equals("HOST")) throw new TargetHost();
        boolean blocked = settlementGuard.hasUnsettledBets(tableId);
        authentication.requireUnexpired(jwt);
        if (blocked) throw new UnsettledBets();

        jdbc.update("""
                UPDATE table_invites SET status = 'CANCELLED'
                WHERE table_id = ? AND status = 'PENDING' AND (from_id = ? OR user_id = ?)
                """, tableId, targetId, targetId);
        int deleted = jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", tableId, targetId);
        if (deleted != 1) throw new IllegalStateException("Locked table member disappeared");
        chatMessages.system(tableId, "A member was removed");
        outbox.append(OutboxEvent.TABLE_MEMBER_REMOVED, tableId,
                Map.of("tableId", tableId, "removedUserId", targetId));
        authentication.requireUnexpired(jwt);
        return Outcome.REMOVED;
    }

    private record Member(UUID userId, String role) {}
}

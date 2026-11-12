package com.p2picks.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableCreationService {
    private static final String CREATE = "table.create.v1";
    private static final String ADD = "table.members.add.v1";

    enum Outcome { SUCCESS, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record MemberOutcome(UUID userId, String outcome) {}
    record Result(Outcome outcome, UUID tableId, List<MemberOutcome> members) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final SystemChatWriter systemChat;

    TableCreationService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, ObjectMapper json, SystemChatWriter systemChat) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.json = json;
        this.systemChat = systemChat;
    }

    @Transactional
    public Result create(Jwt jwt, String key, String name, String emoji, List<UUID> requested) {
        UUID actor = UUID.fromString(jwt.getSubject());
        Map<UUID, String> statuses = authentication.lockActiveWithTargets(jwt, requested);
        if (statuses == null) return failure(Outcome.UNAUTHORIZED);
        authentication.requireUnexpired(jwt);
        String canonical = name.length() + ":" + name + emoji.length() + ":" + emoji + requested;
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, CREATE, canonical,
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return failure(Outcome.CONFLICT);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            UUID tableId = idempotency.responseReference(actor, key, CREATE);
            String role = lockTableAndRole(tableId, actor);
            authentication.requireUnexpired(jwt);
            if (role == null) return failure(Outcome.NOT_FOUND);
            List<MemberOutcome> outcomes = readOutcomes(actor, key, CREATE);
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.SUCCESS, tableId, outcomes);
        }

        UUID tableId = UUID.randomUUID();
        insertTable(tableId, name, emoji);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", tableId, actor);
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'table', ?, 'table.created.v1', jsonb_build_object('tableId', ?::text))
                """, UUID.randomUUID(), tableId.toString(), tableId.toString());
        systemChat.write(tableId, "Table created");
        List<MemberOutcome> outcomes = addRequested(tableId, actor, requested, statuses);
        idempotency.saveResponseReference(actor, key, CREATE, tableId);
        idempotency.saveResponseData(actor, key, CREATE, writeOutcomes(outcomes));
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.SUCCESS, tableId, outcomes);
    }

    @Transactional
    public Result add(Jwt jwt, UUID tableId, String key, List<UUID> requested) {
        UUID actor = UUID.fromString(jwt.getSubject());
        Map<UUID, String> statuses = authentication.lockActiveWithTargets(jwt, requested);
        if (statuses == null) return failure(Outcome.UNAUTHORIZED);
        authentication.requireUnexpired(jwt);
        String role = lockTableAndRole(tableId, actor);
        authentication.requireUnexpired(jwt);
        if (role == null) return failure(Outcome.NOT_FOUND);
        if (!role.equals("HOST")) return failure(Outcome.FORBIDDEN);
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, ADD, tableId + "|" + requested,
                IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return failure(Outcome.CONFLICT);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            List<MemberOutcome> outcomes = readOutcomes(actor, key, ADD);
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.SUCCESS, tableId, outcomes);
        }
        List<MemberOutcome> outcomes = addRequested(tableId, actor, requested, statuses);
        idempotency.saveResponseData(actor, key, ADD, writeOutcomes(outcomes));
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.SUCCESS, tableId, outcomes);
    }

    private String lockTableAndRole(UUID tableId, UUID actor) {
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) return null;
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, actor);
        return roles.isEmpty() ? null : roles.getFirst();
    }

    private void insertTable(UUID tableId, String name, String emoji) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int inserted = jdbc.update("""
                    INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)
                    ON CONFLICT (quickpick_code) DO NOTHING
                    """, tableId, name, emoji, QuickPickCodes.generate());
            if (inserted == 1) return;
        }
        throw new IllegalStateException("Could not allocate a unique Quick Pick code");
    }

    private List<MemberOutcome> addRequested(UUID tableId, UUID actor, List<UUID> requested,
            Map<UUID, String> statuses) {
        List<MemberOutcome> outcomes = new ArrayList<>();
        boolean added = false;
        for (UUID target : requested) {
            String outcome = "not_added";
            if (!actor.equals(target) && "ACTIVE".equals(statuses.get(target))) {
                List<String> modes = jdbc.query("""
                        SELECT CASE
                            WHEN EXISTS (SELECT 1 FROM table_members WHERE table_id = ? AND user_id = ?)
                              OR EXISTS (SELECT 1 FROM table_invites
                                  WHERE table_id = ? AND user_id = ? AND status = 'PENDING')
                              OR EXISTS (SELECT 1 FROM blocks WHERE
                                  (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                              OR NOT EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?)
                            THEN 'closed'
                            WHEN EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?)
                            THEN COALESCE((SELECT friends FROM invite_preferences WHERE user_id = ?), 'open')
                            ELSE COALESCE((SELECT following FROM invite_preferences WHERE user_id = ?), 'request')
                        END AS mode
                        """, (rs, row) -> rs.getString("mode"),
                        tableId, target, tableId, target, actor, target, target, actor,
                        target, actor, actor, target, target, target);
                String mode = modes.getFirst();
                if (mode.equals("open")) {
                    jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                            tableId, target);
                    jdbc.update("""
                            INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                            VALUES (?, 'table', ?, 'table.member.joined.v1',
                                    jsonb_build_object('tableId', ?::text, 'userId', ?::text))
                            """, UUID.randomUUID(), tableId.toString(), tableId.toString(), target.toString());
                    outcome = "added";
                    added = true;
                } else if (mode.equals("request")) {
                    UUID inviteId = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                            VALUES (?, ?, ?, ?, 'PENDING')
                            """, inviteId, tableId, actor, target);
                    jdbc.update("""
                            INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                            VALUES (?, 'user', ?, 'table.invitation.created.v1',
                                    jsonb_build_object('invitationId', ?::text))
                            """, UUID.randomUUID(), target.toString(), inviteId.toString());
                    outcome = "invited";
                }
            }
            outcomes.add(new MemberOutcome(target, outcome));
        }
        if (added) systemChat.write(tableId, "Members were added to the table");
        return List.copyOf(outcomes);
    }

    private String writeOutcomes(List<MemberOutcome> outcomes) {
        try {
            return json.writeValueAsString(outcomes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize membership outcomes", exception);
        }
    }

    private List<MemberOutcome> readOutcomes(UUID actor, String key, String operation) {
        try {
            return List.copyOf(Arrays.asList(json.readValue(idempotency.responseData(actor, key, operation),
                    MemberOutcome[].class)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot deserialize membership outcomes", exception);
        }
    }

    private static Result failure(Outcome outcome) {
        return new Result(outcome, null, List.of());
    }
}

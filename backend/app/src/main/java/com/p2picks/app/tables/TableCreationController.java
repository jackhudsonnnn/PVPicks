package com.p2picks.app.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableCreationController {
    private static final int MAX_MEMBERS = 100;
    private final TableCreationService tables;
    private final TableReadController tableReads;

    TableCreationController(TableCreationService tables, TableReadController tableReads) {
        this.tables = tables;
        this.tableReads = tableReads;
    }

    @PostMapping("/tables")
    ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || !onlyFields(input, Set.of("name", "emoji", "memberIds"))
                || !input.path("name").isTextual() || !input.path("emoji").isTextual()) {
            return error(400, "bad_request", "Invalid table creation request.");
        }
        String name = PostgresText.trim(input.path("name").textValue());
        String emoji = input.path("emoji").textValue();
        List<UUID> members = input.has("memberIds") ? parseIds(input.get("memberIds"), false) : List.of();
        if (!TableProfileValidation.validName(name) || !TableProfileValidation.validEmoji(emoji) || members == null) {
            return error(400, "bad_request", "Invalid table creation request.");
        }
        try {
            TableCreationService.Result result = tables.create(jwt, key, name, emoji, members);
            if (result.outcome() != TableCreationService.Outcome.SUCCESS) return failure(result.outcome());
            ResponseEntity<?> table = tableReads.get(jwt, result.tableId());
            if (!table.getStatusCode().is2xxSuccessful()) return table;
            return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                    .body(new CreatedTable(table.getBody(), result.members()));
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    @PostMapping("/tables/{tableId}/members")
    ResponseEntity<?> add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 1 || !input.has("userIds")) {
            return error(400, "bad_request", "Invalid member request.");
        }
        List<UUID> members = parseIds(input.get("userIds"), true);
        if (members == null) return error(400, "bad_request", "Invalid member request.");
        try {
            TableCreationService.Result result = tables.add(jwt, tableId, key, members);
            if (result.outcome() != TableCreationService.Outcome.SUCCESS) return failure(result.outcome());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.members());
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static boolean onlyFields(JsonNode input, Set<String> allowed) {
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }

    private static List<UUID> parseIds(JsonNode input, boolean required) {
        if (!input.isArray() || input.size() > MAX_MEMBERS || required && input.isEmpty()) return null;
        List<UUID> ids = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode value : input) {
            if (!value.isTextual()) return null;
            UUID id;
            try {
                id = UUID.fromString(value.textValue());
            } catch (IllegalArgumentException exception) {
                return null;
            }
            if (!id.toString().equalsIgnoreCase(value.textValue()) || !seen.add(id)) return null;
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static ResponseEntity<?> failure(TableCreationService.Outcome outcome) {
        return switch (outcome) {
            case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
            case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            case FORBIDDEN -> error(403, "forbidden", "Only a host can add members.");
            case NOT_FOUND -> error(404, "not_found", "Table not found.");
            case SUCCESS -> throw new IllegalArgumentException("Expected a failed table operation");
        };
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }

    private record CreatedTable(Object table, List<TableCreationService.MemberOutcome> outcomes) {}
}

package com.p2picks.app.moderation;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportController {
    private static final Set<String> REASONS = Set.of("harassment", "settlement", "spam", "offensive", "other");
    private static final Set<String> FIELDS = Set.of("targetId", "reason", "note", "tableId", "messageId");
    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/reports")
    ResponseEntity<?> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        ReportService.Submission submission = parse(input);
        if (submission == null) return error(400, "bad_request", "Invalid report.");
        try {
            return switch (reports.submit(jwt, key, submission)) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
                case INVALID -> error(400, "bad_request", "Invalid report.");
                case RATE_LIMITED -> error(429, "rate_limited", "Too many reports today. Try again later.");
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ReportService.Submission parse(JsonNode input) {
        if (!input.isObject() || !input.path("targetId").isTextual() || !input.path("reason").isTextual()
                || !input.path("note").isTextual() || !REASONS.contains(input.path("reason").textValue())
                || input.has("tableId") && !input.path("tableId").isTextual()
                || input.has("messageId") && !input.path("messageId").isTextual()) {
            return null;
        }
        var names = input.fieldNames();
        while (names.hasNext()) {
            if (!FIELDS.contains(names.next())) return null;
        }
        String note = PostgresText.trim(input.path("note").textValue());
        String reason = input.path("reason").textValue();
        if (note.length() > 500 || !PostgresText.valid(note) || reason.equals("other") && note.isEmpty()) return null;
        try {
            UUID table = input.has("tableId") ? UUID.fromString(input.path("tableId").textValue()) : null;
            UUID message = input.has("messageId") ? UUID.fromString(input.path("messageId").textValue()) : null;
            if (message != null && table == null) return null;
            return new ReportService.Submission(UUID.fromString(input.path("targetId").textValue()), reason, note,
                    table, message);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}

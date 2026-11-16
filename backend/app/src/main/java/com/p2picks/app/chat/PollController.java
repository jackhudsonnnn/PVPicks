package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PollController {
    static final int QUESTION_LIMIT = 200;
    static final int OPTION_LIMIT = 80;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;

    private final PollService polls;

    PollController(PollService polls) {
        this.polls = polls;
    }

    @PostMapping("/tables/{tableId}/polls")
    ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 2 || !input.path("question").isTextual()
                || !input.path("options").isArray()) {
            return error(400, "bad_request", "A poll needs a question and its options.");
        }
        String question = clean(input.path("question").textValue(), QUESTION_LIMIT);
        if (question == null) {
            return error(400, "bad_request", "The question must be 1 to " + QUESTION_LIMIT + " characters.");
        }
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode option : input.path("options")) {
            String text = option.isTextual() ? clean(option.textValue(), OPTION_LIMIT) : null;
            if (text == null || !seen.add(text.toLowerCase(Locale.ROOT))) {
                return error(400, "bad_request",
                        "Options must be different, each 1 to " + OPTION_LIMIT + " characters.");
            }
            options.add(text);
        }
        if (options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            return error(400, "bad_request", "A poll has " + MIN_OPTIONS + " to " + MAX_OPTIONS + " options.");
        }
        try {
            PollService.Created result = polls.create(jwt, tableId, key, question, options);
            return switch (result.outcome()) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                        .body(result.message());
                default -> failure(result.outcome());
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return failure(PollService.Outcome.UNAUTHORIZED);
        }
    }

    @PutMapping("/messages/{messageId}/vote")
    ResponseEntity<?> vote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 1 || !input.path("option").isInt()
                || input.path("option").intValue() < 0 || input.path("option").intValue() >= MAX_OPTIONS) {
            return error(400, "bad_request", "Vote for one of the poll's options.");
        }
        return change(jwt, messageId, input.path("option").intValue(), key);
    }

    @DeleteMapping("/messages/{messageId}/vote")
    ResponseEntity<?> unvote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (body != null && body.length != 0) {
            return error(400, "bad_request", "This operation does not accept a request body.");
        }
        return change(jwt, messageId, null, key);
    }

    private ResponseEntity<?> change(Jwt jwt, UUID messageId, Integer option, String key) {
        try {
            PollService.Outcome outcome = polls.vote(jwt, messageId, option, key);
            return outcome == PollService.Outcome.UPDATED
                    ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                    : failure(outcome);
        } catch (MutationAuthentication.ExpiredSession exception) {
            return failure(PollService.Outcome.UNAUTHORIZED);
        }
    }

    private static ResponseEntity<?> failure(PollService.Outcome outcome) {
        return switch (outcome) {
            case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
            case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            case FORBIDDEN -> error(403, "forbidden", "Poll permission is disabled.");
            case POLLS_OFF -> error(403, "polls_off", "Polls are turned off at this table.");
            case BAD_OPTION -> error(400, "bad_request", "Vote for one of the poll's options.");
            default -> error(404, "not_found", "Table or poll not found.");
        };
    }

    /** Trimmed text of 1 to {@code limit} characters that PostgreSQL can store, or null. */
    private static String clean(String value, int limit) {
        String text = PostgresText.trim(value);
        return !text.isEmpty() && PostgresText.valid(text) && text.codePointCount(0, text.length()) <= limit
                ? text : null;
    }
}

package com.p2picks.app.feedback;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FeedbackController {
    private static final Set<String> KINDS = Set.of("mode", "perk", "gif", "bug", "other");
    private static final Set<String> FIELDS = Set.of("kind", "text", "link");
    private static final Pattern LINK_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private final FeedbackService feedback;

    FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/feedback")
    ResponseEntity<?> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        FeedbackService.Submission submission = parse(input);
        if (submission == null) {
            return error(400, "bad_request", "Invalid feedback.");
        }
        try {
            return switch (feedback.submit(jwt, key, submission)) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static FeedbackService.Submission parse(JsonNode input) {
        if (!input.isObject() || !input.path("kind").isTextual() || !input.path("text").isTextual()
                || !KINDS.contains(input.path("kind").textValue())
                || input.has("link") && !input.path("link").isTextual()) {
            return null;
        }
        var names = input.fieldNames();
        while (names.hasNext()) {
            if (!FIELDS.contains(names.next())) return null;
        }
        String text = PostgresText.trim(input.path("text").textValue());
        if (text.length() < 10 || text.length() > 1000 || !PostgresText.valid(text)) {
            return null;
        }
        String link = input.has("link") ? PostgresText.trim(input.path("link").textValue()) : "";
        if (!link.isEmpty()) {
            var prefix = LINK_PREFIX.matcher(link);
            if (!PostgresText.valid(link) || !prefix.find() || prefix.end() == link.length()
                    || link.codePoints().anyMatch(PostgresText::jsWhitespace)) {
                return null;
            }
        }
        return new FeedbackService.Submission(input.path("kind").textValue(), text, link.isEmpty() ? null : link);
    }
}

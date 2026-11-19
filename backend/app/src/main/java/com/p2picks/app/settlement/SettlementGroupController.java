package com.p2picks.app.settlement;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SettlementGroupController {
    private record GroupView(UUID otherUserId, long netCents, List<SettlementReceiptService.Line> lines,
                             @JsonInclude(JsonInclude.Include.ALWAYS) UUID pendingReviewRevision,
                             List<SettleService.SettleView> pendingSettles) {}
    private record ReviewView(@JsonInclude(JsonInclude.Include.ALWAYS) UUID pendingReviewRevision) {}
    private record SettlePage(List<SettleService.SettleView> items,
                              @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {}

    private final SettlementGroupService groups;
    private final SettlementReviewService reviews;
    private final SettleService settles;

    SettlementGroupController(SettlementGroupService groups, SettlementReviewService reviews, SettleService settles) {
        this.groups = groups;
        this.reviews = reviews;
        this.settles = settles;
    }

    @GetMapping("/people/settle-groups")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        var result = groups.read(jwt);
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        List<GroupView> views = new ArrayList<>();
        for (var group : result.groups()) {
            long net;
            try {
                net = group.netCents().longValueExact();
            } catch (ArithmeticException exception) {
                return error(409, "group_net_out_of_range", "A receipt group total exceeds the supported range.");
            }
            views.add(new GroupView(group.otherUserId(), net, group.lines(), group.pendingReviewRevision(),
                    group.pendingSettles()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.copyOf(views));
    }

    /** Settle chosen open receipts with one person: {"kind": "sent"|"received", "lineIds": [...]}. */
    @PostMapping("/people/{userId}/settles")
    ResponseEntity<?> settle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        SettleRules.Kind kind = input.isObject() && input.size() == 2 && input.path("kind").isTextual()
                ? SettleRules.Kind.of(input.path("kind").textValue()) : null;
        List<UUID> lineIds = new ArrayList<>();
        JsonNode ids = input.path("lineIds");
        if (kind == null || !ids.isArray() || ids.isEmpty() || ids.size() > SettleRules.MAX_LINES) {
            return error(400, "bad_request", "A settle kind and 1 to 500 receipt IDs are required.");
        }
        for (JsonNode id : ids) {
            try {
                UUID parsed = UUID.fromString(id.textValue());
                if (!id.isTextual() || !parsed.toString().equalsIgnoreCase(id.textValue()) || lineIds.contains(parsed)) {
                    throw new IllegalArgumentException("Invalid receipt ID");
                }
                lineIds.add(parsed);
            } catch (RuntimeException exception) {
                return error(400, "bad_request", "A settle kind and 1 to 500 receipt IDs are required.");
            }
        }
        try {
            var result = settles.create(jwt, userId, kind, lineIds, key);
            return switch (result.outcome()) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                        .body(result.settle());
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> error(404, "not_found", "No receipts with this person.");
                case BAD_REQUEST -> error(400, "bad_request", "A settle needs two different people.");
                case REJECTED -> throw new IllegalStateException("Creating a settle can't reject one");
            };
        } catch (SettleService.StaleSelection exception) {
            return error(409, "stale_selection", "Some of those receipts changed. Refresh and choose again.");
        } catch (SettleService.InvalidSettle exception) {
            return error(409, "invalid_settle", exception.getMessage());
        } catch (SettleService.SendLimit exception) {
            return error(429, "settle_limit", "You two have settled " + SettleRules.SENDS_PER_DAY
                    + " times in the last 24 hours. Try again later.");
        } catch (SettlementReviewService.ReviewRequired exception) {
            return error(409, "settlement_review_required", "Review the balance change before settling.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    /** The receiver rejects a sent settle within its window; its receipts reopen. */
    @PostMapping("/settles/{settleId}/reject")
    ResponseEntity<?> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID settleId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        try {
            var result = settles.reject(jwt, settleId, key);
            return switch (result.outcome()) {
                case REJECTED, REPLAYED -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(result.settle());
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> error(404, "not_found", "Settle not found.");
                case CREATED, BAD_REQUEST -> throw new IllegalStateException("Rejecting can't create a settle");
            };
        } catch (SettleService.NotRejectable exception) {
            return error(409, "not_rejectable", "This settle can't be rejected any more.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    /** Every settle with one person, newest first, a page at a time. */
    @GetMapping("/people/{userId}/settles")
    ResponseEntity<?> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.keySet().stream().anyMatch(key -> !key.equals("cursor"))
                || parameters.values().stream().anyMatch(values -> values.size() != 1)) {
            return error(400, "bad_request", "Only a cursor is accepted.");
        }
        SettleService.Page page;
        try {
            page = settles.history(jwt, userId, parameters.getFirst("cursor"));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
            return error(400, "bad_request", "Invalid cursor.");
        }
        if (!page.authenticated()) return unauthorized();
        if (!page.player()) return forbidden();
        if (!page.accessible()) return error(404, "not_found", "No receipts with this person.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SettlePage(page.items(), page.nextCursor()));
    }

    @GetMapping("/users/{userId}/settlement-review")
    ResponseEntity<?> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        var result = reviews.read(jwt, userId);
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        if (!result.accessible()) return error(404, "not_found", "Settlement review not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReviewView(result.pendingReviewRevision()));
    }

    @PostMapping("/users/{userId}/settlement-review/acknowledge")
    ResponseEntity<?> acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        UUID revision;
        try {
            if (!input.isObject() || input.size() != 1 || !input.path("revision").isTextual()) {
                throw new IllegalArgumentException("Invalid revision");
            }
            String value = input.path("revision").textValue();
            revision = UUID.fromString(value);
            if (!revision.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid revision");
        } catch (IllegalArgumentException exception) {
            return error(400, "bad_request", "A valid review revision is required.");
        }
        try {
            return switch (reviews.acknowledge(jwt, userId, revision, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> error(404, "not_found", "Settlement review not found.");
                case BAD_REQUEST -> error(400, "bad_request", "A settlement review requires two different users.");
            };
        } catch (SettlementReviewService.StaleReview exception) {
            return error(409, "stale_review", "The balance-change warning has changed; refresh and review it.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return error(403, "forbidden", "The operation is not permitted.");
    }
}

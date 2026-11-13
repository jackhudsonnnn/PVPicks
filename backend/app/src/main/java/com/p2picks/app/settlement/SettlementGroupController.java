package com.p2picks.app.settlement;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.BettingLifecycleReady;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@ConditionalOnBean(BettingLifecycleReady.class)
class SettlementGroupController {
    private record GroupView(UUID otherUserId, long netCents, String revision, List<SettlementReceiptService.Line> lines,
                             @JsonInclude(JsonInclude.Include.ALWAYS) UUID pendingReviewRevision) {}
    private record ReviewView(@JsonInclude(JsonInclude.Include.ALWAYS) UUID pendingReviewRevision) {}

    private final SettlementGroupService groups;
    private final SettlementReviewService reviews;

    SettlementGroupController(SettlementGroupService groups, SettlementReviewService reviews) {
        this.groups = groups;
        this.reviews = reviews;
    }

    @GetMapping("/people/settlement-reviews")
    ResponseEntity<?> pendingReviews(@AuthenticationPrincipal Jwt jwt) {
        var result = reviews.listPending(jwt);
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.reviews());
    }

    @GetMapping("/people/settle-groups")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.size() > 1 || parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.keySet().stream().anyMatch(key -> !key.equals("includeHistory"))) {
            return error(400, "bad_request", "Provide at most one valid includeHistory value.");
        }
        String includeHistory = parameters.getFirst("includeHistory");
        if (includeHistory != null && !includeHistory.equals("true") && !includeHistory.equals("false")) {
            return error(400, "bad_request", "Provide at most one valid includeHistory value.");
        }
        var result = groups.read(jwt, "true".equals(includeHistory));
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
            views.add(new GroupView(group.otherUserId(), net, group.revision(), group.lines(), group.pendingReviewRevision()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.copyOf(views));
    }

    @PostMapping("/people/{userId}/settle")
    ResponseEntity<?> transition(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 2 || !input.path("step").isTextual()
                || !input.path("expectedRevision").isTextual() || input.path("expectedRevision").textValue().isEmpty()) {
            return error(400, "bad_request", "A valid group action and revision are required.");
        }
        SettlementGroupService.Action action = switch (input.path("step").textValue()) {
            case "sent" -> SettlementGroupService.Action.SENT;
            case "paid" -> SettlementGroupService.Action.PAID;
            case "unpaid" -> SettlementGroupService.Action.UNPAID;
            case "forgiven" -> SettlementGroupService.Action.FORGIVEN;
            case "ask" -> SettlementGroupService.Action.ASK;
            case "decline" -> SettlementGroupService.Action.DECLINE;
            default -> null;
        };
        if (action == null) return error(400, "bad_request", "A valid group action and revision are required.");
        try {
            return switch (groups.transition(jwt, userId, action, input.path("expectedRevision").textValue(), key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> error(404, "not_found", "Receipt group not found.");
                case BAD_REQUEST -> error(400, "bad_request", "A receipt group requires two different users.");
            };
        } catch (SettlementGroupService.StaleRevision exception) {
            return error(409, "stale_revision", "The receipt group has changed; refresh and retry.");
        } catch (SettlementGroupService.InvalidTransition exception) {
            return error(409, "invalid_transition", "The action is not valid for this receipt group.");
        } catch (SettlementReviewService.ReviewRequired exception) {
            return error(409, "settlement_review_required", "Review the balance change before continuing.");
        } catch (SettlementGroupService.NoOpenReceipts exception) {
            return error(409, "no_open_receipts", "There are no open receipts in this group.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
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

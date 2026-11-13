package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
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
class SettlementReceiptController {
    private final SettlementReceiptService receipts;

    SettlementReceiptController(SettlementReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping("/people/settlement-counterparties")
    ResponseEntity<?> counterparties(@AuthenticationPrincipal Jwt jwt) {
        var result = receipts.counterparties(jwt);
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.userIds());
    }

    @GetMapping("/users/{userId}/settlements")
    ResponseEntity<?> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.size() > 1 || parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.keySet().stream().anyMatch(key -> !key.equals("tableId"))) {
            return error(400, "bad_request", "Provide at most one valid table ID.");
        }
        UUID tableId = null;
        if (parameters.containsKey("tableId")) {
            String value = parameters.getFirst("tableId");
            try {
                tableId = UUID.fromString(value);
                if (!tableId.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID");
            } catch (IllegalArgumentException exception) {
                return error(400, "bad_request", "Provide at most one valid table ID.");
            }
        }
        var result = receipts.history(jwt, userId, tableId);
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.lines());
    }

    @PostMapping("/settlement-lines/{lineId}/{action}")
    ResponseEntity<?> transition(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID lineId,
            @PathVariable String action, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        SettlementReceiptService.Action parsedAction = switch (action) {
            case "sent" -> SettlementReceiptService.Action.SENT;
            case "confirm" -> SettlementReceiptService.Action.CONFIRM;
            case "dispute" -> SettlementReceiptService.Action.DISPUTE;
            case "forgive" -> SettlementReceiptService.Action.FORGIVE;
            case "ask-forgive" -> SettlementReceiptService.Action.ASK_FORGIVE;
            case "decline-forgive" -> SettlementReceiptService.Action.DECLINE_FORGIVE;
            default -> null;
        };
        JsonNode revision = input.path("revision");
        if (parsedAction == null || !input.isObject() || input.size() != 1 || !revision.isIntegralNumber()
                || !revision.canConvertToInt() || revision.intValue() < 0) {
            return error(400, "bad_request", "A valid action and receipt revision are required.");
        }
        try {
            var result = receipts.transition(jwt, lineId, parsedAction, revision.intValue(), key);
            return switch (result.outcome()) {
                case UPDATED, REPLAYED -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.line());
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> error(404, "not_found", "Receipt not found.");
            };
        } catch (SettlementReceiptService.StaleRevision exception) {
            return error(409, "stale_revision", "The receipt has changed; refresh and retry.");
        } catch (SettlementReceiptService.InvalidTransition exception) {
            return error(409, "invalid_transition", "The action is not valid for this receipt.");
        } catch (SettlementReviewService.ReviewRequired exception) {
            return error(409, "settlement_review_required", "Review the balance change before continuing.");
        } catch (SettlementReceiptService.AffectedUsersChanged exception) {
            return error(409, "receipt_affected_users_changed", "Affected users changed; retry the request.");
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

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

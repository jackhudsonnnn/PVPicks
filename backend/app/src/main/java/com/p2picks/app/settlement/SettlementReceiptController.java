package com.p2picks.app.settlement;

import static com.p2picks.app.common.ApiErrors.error;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SettlementReceiptController {
    private final SettlementReceiptService receipts;

    SettlementReceiptController(SettlementReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping("/users/{userId}/settlements")
    ResponseEntity<?> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.keySet().stream().anyMatch(key -> !key.equals("tableId") && !key.equals("cursor"))) {
            return error(400, "bad_request", "Provide at most one valid table ID and cursor.");
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
        SettlementReceiptService.History result;
        try {
            result = receipts.history(jwt, userId, tableId, parameters.getFirst("cursor"));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            return error(400, "bad_request", "Invalid cursor.");
        }
        if (!result.authenticated()) return unauthorized();
        if (!result.player()) return forbidden();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("open", result.open());
        body.put("closed", result.closed());
        body.put("nextCursor", result.nextCursor());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return error(403, "forbidden", "The operation is not permitted.");
    }
}

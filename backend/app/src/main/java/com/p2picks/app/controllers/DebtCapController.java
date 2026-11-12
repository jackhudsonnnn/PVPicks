package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DebtCapController {
    private final DebtCapService caps;

    DebtCapController(DebtCapService caps) {
        this.caps = caps;
    }

    @PutMapping("/me/debt-cap")
    ResponseEntity<?> replace(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (!input.isObject() || input.size() != 1 || !input.has("capCents")) {
            return error(400, "bad_request", "Invalid debt cap.");
        }
        JsonNode value = input.path("capCents");
        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() % 100 != 0)) {
            return error(400, "bad_request", "Debt cap must be a nonnegative whole number of points.");
        }
        Integer capCents = value.isNull() ? null : value.intValue();
        try {
            return switch (caps.replace(jwt, key, capCents)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (DebtCapService.OutstandingDebt exception) {
            return error(409, "conflict", "Clear all debt before changing the debt cap.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

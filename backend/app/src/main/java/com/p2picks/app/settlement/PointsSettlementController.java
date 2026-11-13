package com.p2picks.app.settlement;

import com.p2picks.app.bets.BettingLifecycleReady;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@ConditionalOnBean(BettingLifecycleReady.class)
class PointsSettlementController {
    private final TablePointsSettlementService settlements;

    PointsSettlementController(TablePointsSettlementService settlements) {
        this.settlements = settlements;
    }

    @PostMapping("/tables/{tableId}/settle-points")
    ResponseEntity<?> settle(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a body.");
        try {
            var result = settlements.settle(jwt, tableId, key);
            return switch (result.outcome()) {
                case CREATED, REPLAYED -> {
                    var view = settlements.readForHost(jwt, result.settlementId());
                    if (!view.authenticated()) yield unauthorized();
                    if (view.settlement() == null) yield notFound();
                    yield ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(view.settlement());
                }
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> error(403, "forbidden", "The operation is not permitted.");
                case NOT_FOUND -> notFound();
            };
        } catch (TablePointsSettlementService.NoEligibleBets exception) {
            return error(409, "no_eligible_bets", "There are no resolved bets to settle.");
        } catch (TablePointsSettlementService.AffectedUsersChanged exception) {
            return error(409, "settlement_affected_users_changed", "Affected users changed; retry the request.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<Map<String, String>> notFound() {
        return error(404, "not_found", "Table or settlement not found.");
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

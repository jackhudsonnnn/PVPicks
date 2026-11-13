package com.p2picks.app.settlement;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DebtCapController {
    private final DebtCapService caps;
    private final JdbcTemplate jdbc;
    private final DebtQuery debtQuery;

    DebtCapController(DebtCapService caps, JdbcTemplate jdbc, DebtQuery debtQuery) {
        this.caps = caps;
        this.jdbc = jdbc;
        this.debtQuery = debtQuery;
    }

    @GetMapping("/me/debt")
    ResponseEntity<?> debt(@AuthenticationPrincipal Jwt jwt) {
        var amount = debtQuery.read(jwt);
        if (amount.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Debt(amount.orElseThrow().longValueExact()));
    }

    @GetMapping("/me/debt-cap")
    ResponseEntity<?> debtCap(@AuthenticationPrincipal Jwt jwt) {
        List<DebtCap> saved = jdbc.query("""
                SELECT cap.cap_cents
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN debt_caps cap ON cap.user_id = viewer.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new DebtCap(rs.getObject("cap_cents", Integer.class)),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (saved.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved.getFirst());
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


    private record DebtCap(@JsonInclude(JsonInclude.Include.ALWAYS) Integer capCents) {}

    private record Debt(long debtCents) {}
}

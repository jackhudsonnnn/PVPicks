package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A Hold'em game: who's in, the cards you may see, and the players' moves (domain rules, "Hold'em"). */
@RestController
class HoldemController {
    private final HoldemService holdem;
    private final JdbcTemplate jdbc;

    HoldemController(HoldemService holdem, JdbcTemplate jdbc) {
        this.holdem = holdem;
        this.jdbc = jdbc;
    }

    @GetMapping("/bets/{betId}/holdem")
    ResponseEntity<?> game(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Boolean session = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                      AND u.status = 'ACTIVE')
                """, Boolean.class, UUID.fromString(jwt.getId()), userId);
        if (!Boolean.TRUE.equals(session)) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bets b JOIN tables t ON t.id = b.table_id
                    WHERE b.id = ? AND b.mode = 'holdem' AND (
                        (t.archived_at IS NULL AND EXISTS (SELECT 1 FROM table_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))
                        OR (t.archived_at IS NOT NULL AND EXISTS (SELECT 1 FROM table_archive_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))))
                """, Boolean.class, betId, userId, userId);
        if (!Boolean.TRUE.equals(visible)) return error(404, "not_found", "Bet or table not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holdem.view(betId, userId));
    }

    @PutMapping("/bets/{betId}/holdem/seat")
    ResponseEntity<?> join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        return run(() -> holdem.join(jwt, betId, key));
    }

    @DeleteMapping("/bets/{betId}/holdem/seat")
    ResponseEntity<?> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        return run(() -> holdem.leave(jwt, betId, key));
    }

    /** {@code {action}} or, for a raise, {@code {action: "raise", amount}} (the raise-to total for this street). */
    @PostMapping("/bets/{betId}/holdem/actions")
    ResponseEntity<?> act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        String action = input.path("action").asText("");
        boolean raise = action.equals("raise");
        boolean valid = input.isObject() && input.path("action").isTextual() && HoldemService.ACTIONS.contains(action)
                && input.size() == (raise ? 2 : 1)
                && (!raise || (input.path("amount").isIntegralNumber() && input.path("amount").canConvertToLong()
                        && input.path("amount").longValue() > 0));
        if (!valid) return error(400, "bad_request", "Invalid action.");
        long amount = raise ? input.path("amount").longValue() : 0;
        return run(() -> holdem.act(jwt, betId, action, amount, key));
    }

    private static ResponseEntity<?> run(Supplier<HoldemService.Outcome> call) {
        try {
            return switch (call.get()) {
                case DONE, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "The operation is not permitted.");
                case NOT_FOUND -> error(404, "not_found", "Bet or table not found.");
            };
        } catch (HoldemService.Refused refused) {
            return error(409, "holdem_refused", refused.getMessage());
        } catch (HoldemService.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "Your points limit can't cover two buy-ins at this table.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> badKey() {
        return error(400, "bad_request", "A valid Idempotency-Key is required.");
    }
}

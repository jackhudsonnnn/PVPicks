package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.Set;
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

/** A Blackjack hand: who's sitting, the cards, and the players' decisions (domain rules, "Blackjack"). */
@RestController
class BlackjackController {
    private static final Set<String> ACTIONS = Set.of("hit", "stand", "double", "split", "insurance", "no_insurance",
            "dealer");
    private final BlackjackService blackjack;
    private final JdbcTemplate jdbc;

    BlackjackController(BlackjackService blackjack, JdbcTemplate jdbc) {
        this.blackjack = blackjack;
        this.jdbc = jdbc;
    }

    @GetMapping("/bets/{betId}/blackjack")
    ResponseEntity<?> hand(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId) {
        Boolean session = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                      AND u.status = 'ACTIVE')
                """, Boolean.class, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (!Boolean.TRUE.equals(session)) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bets b JOIN tables t ON t.id = b.table_id
                    WHERE b.id = ? AND b.mode = 'blackjack' AND (
                        (t.archived_at IS NULL AND EXISTS (SELECT 1 FROM table_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))
                        OR (t.archived_at IS NOT NULL AND EXISTS (SELECT 1 FROM table_archive_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))))
                """, Boolean.class, betId, UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getSubject()));
        if (!Boolean.TRUE.equals(visible)) return error(404, "not_found", "Bet or table not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(blackjack.view(betId));
    }

    @PutMapping("/bets/{betId}/blackjack/seat")
    ResponseEntity<?> sit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        if (!input.isObject() || input.size() != 1 || !input.path("betCents").isIntegralNumber()
                || !input.path("betCents").canConvertToLong()) {
            return error(400, "bad_request", "Invalid bet.");
        }
        long betCents = input.path("betCents").longValue();
        return run(() -> blackjack.sit(jwt, betId, betCents, key));
    }

    @DeleteMapping("/bets/{betId}/blackjack/seat")
    ResponseEntity<?> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        return run(() -> blackjack.leave(jwt, betId, key));
    }

    @PostMapping("/bets/{betId}/blackjack/actions")
    ResponseEntity<?> act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        if (!input.isObject() || input.size() != 1 || !input.path("action").isTextual()
                || !ACTIONS.contains(input.path("action").textValue())) {
            return error(400, "bad_request", "Invalid action.");
        }
        String action = input.path("action").textValue();
        return run(() -> blackjack.act(jwt, betId, action, key));
    }

    private static ResponseEntity<?> run(Supplier<BlackjackService.Outcome> call) {
        try {
            return switch (call.get()) {
                case DONE, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "The operation is not permitted.");
                case NOT_FOUND -> error(404, "not_found", "Bet or table not found.");
            };
        } catch (BlackjackService.Refused refused) {
            return error(409, "blackjack_refused", refused.getMessage());
        } catch (BlackjackService.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "That would put you past your points limit.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> badKey() {
        return error(400, "bad_request", "A valid Idempotency-Key is required.");
    }
}

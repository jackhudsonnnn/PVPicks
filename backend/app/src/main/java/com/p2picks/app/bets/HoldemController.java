package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import java.util.UUID;
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
        return CardGameResponses.view(jdbc, jwt, betId, HoldemService.MODE,
                () -> holdem.view(betId, UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/bets/{betId}/holdem/seat")
    ResponseEntity<?> join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        return CardGameResponses.run("holdem_refused", () -> holdem.join(jwt, betId, key));
    }

    @DeleteMapping("/bets/{betId}/holdem/seat")
    ResponseEntity<?> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        return CardGameResponses.run("holdem_refused", () -> holdem.leave(jwt, betId, key));
    }

    /** {@code {action}} or, for a raise, {@code {action: "raise", amount}} (the raise-to total for this street). */
    @PostMapping("/bets/{betId}/holdem/actions")
    ResponseEntity<?> act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        String action = input.path("action").asText("");
        boolean raise = action.equals("raise");
        boolean valid = input.isObject() && input.path("action").isTextual() && HoldemService.ACTIONS.contains(action)
                && input.size() == (raise ? 2 : 1)
                && (!raise || (input.path("amount").isIntegralNumber() && input.path("amount").canConvertToLong()
                        && input.path("amount").longValue() > 0));
        if (!valid) return error(400, "bad_request", "Invalid action.");
        long amount = raise ? input.path("amount").longValue() : 0;
        return CardGameResponses.run("holdem_refused", () -> holdem.act(jwt, betId, action, amount, key));
    }
}

package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import java.util.Set;
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

/** A Jackblack game: who's in, the cards, and the players' decisions (domain rules, "Jackblack"). */
@RestController
class JackblackController {
    private static final Set<String> ACTIONS = Set.of("hit", "stand", "double", "split", "deal");
    private final JackblackService jackblack;
    private final JdbcTemplate jdbc;

    JackblackController(JackblackService jackblack, JdbcTemplate jdbc) {
        this.jackblack = jackblack;
        this.jdbc = jdbc;
    }

    @GetMapping("/bets/{betId}/jackblack")
    ResponseEntity<?> hand(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId) {
        return CardGameResponses.view(jdbc, jwt, betId, JackblackService.MODE,
                () -> jackblack.view(betId, UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/bets/{betId}/jackblack/seat")
    ResponseEntity<?> join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        return CardGameResponses.run("jackblack_refused", () -> jackblack.join(jwt, betId, key));
    }

    @DeleteMapping("/bets/{betId}/jackblack/seat")
    ResponseEntity<?> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        return CardGameResponses.run("jackblack_refused", () -> jackblack.leave(jwt, betId, key));
    }

    @PostMapping("/bets/{betId}/jackblack/actions")
    ResponseEntity<?> act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        if (!input.isObject() || input.size() != 1 || !input.path("action").isTextual()
                || !ACTIONS.contains(input.path("action").textValue())) {
            return error(400, "bad_request", "Invalid action.");
        }
        String action = input.path("action").textValue();
        return CardGameResponses.run("jackblack_refused", () -> jackblack.act(jwt, betId, action, key));
    }
}

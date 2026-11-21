package com.p2picks.app.accounts;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.security.MutationAuthentication;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin's player accounts: list, create (the response carries the new player's sign-in code), view a player's
 * code, rotate it (which signs the player out everywhere) and delete the account. {@code /admin/**} is admin-only (SessionSecurity).
 * No retry keys: a retried create finds the username taken, and a retried rotate just rotates again.
 */
@RestController
class AdminPlayerController {
    private final PlayerProvisioning players;
    private final AccountDeletionService deletion;

    AdminPlayerController(PlayerProvisioning players, AccountDeletionService deletion) {
        this.players = players;
        this.deletion = deletion;
    }

    @GetMapping("/admin/players")
    ResponseEntity<?> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) String q) {
        if (q != null && q.length() > 15) return error(400, "bad_request", "Search up to 15 characters.");
        try {
            PlayerProvisioning.Page page = players.players(cursor, q);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", page.items());
            body.put("nextCursor", page.nextCursor());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            return error(400, "bad_request", "Invalid cursor.");
        }
    }

    @PostMapping("/admin/players")
    ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input) {
        if (!input.isObject() || input.size() != 1 || !input.path("username").isTextual()) {
            return error(400, "bad_request", "A username is required.");
        }
        return respond(() -> players.create(jwt, input.path("username").textValue()));
    }

    @GetMapping("/admin/players/{playerId}/code")
    ResponseEntity<?> code(@PathVariable UUID playerId) {
        return respond(() -> players.reveal(playerId));
    }

    @PostMapping("/admin/players/{playerId}/code")
    ResponseEntity<?> rotate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID playerId) {
        return respond(() -> players.rotate(jwt, playerId));
    }

    /**
     * Deletes a player outright, square or not: their open bets wash for everyone at those tables (domain rules,
     * "Admin account deletion"). The admin UI warns about that first. The body reports how many bets washed.
     */
    @DeleteMapping("/admin/players/{playerId}")
    ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID playerId) {
        try {
            AccountDeletionService.AdminResult result = deletion.deleteByAdmin(jwt, playerId);
            return switch (result.outcome()) {
                case DELETED -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(Map.of("washedBets", result.washedBets()));
                case WRONG_USERNAME -> error(404, "not_found", "Not found.");
                default -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private ResponseEntity<?> respond(java.util.function.Supplier<PlayerProvisioning.Result> action) {
        try {
            PlayerProvisioning.Result result = action.get();
            if (result instanceof PlayerProvisioning.Result.Done done) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", done.created().id());
                body.put("username", done.created().username());
                body.put("code", done.created().code());
                return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
            }
            if (result instanceof PlayerProvisioning.Result.Code issued) {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code", issued.code()));
            }
            if (result instanceof PlayerProvisioning.Result.Invalid invalid) {
                return error(400, "bad_request", invalid.message());
            }
            if (result instanceof PlayerProvisioning.Result.Taken) {
                return error(409, "username_taken", "That username is taken.");
            }
            if (result instanceof PlayerProvisioning.Result.NotFound) return error(404, "not_found", "Not found.");
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}

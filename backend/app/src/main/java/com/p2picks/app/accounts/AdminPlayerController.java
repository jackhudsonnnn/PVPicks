package com.p2picks.app.accounts;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.security.AccountSetupCodes;
import com.p2picks.app.security.MutationAuthentication;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin's player accounts: list, create (with a setup code) and issue a setup or reset code. {@code /admin/**}
 * is admin-only (SessionSecurity). Codes appear in these responses once and are never stored in plain text, so a
 * lost code is replaced by issuing another (which cancels the old one); that's why these calls take no retry key.
 */
@RestController
class AdminPlayerController {
    private final PlayerProvisioning players;

    AdminPlayerController(PlayerProvisioning players) {
        this.players = players;
    }

    @GetMapping("/admin/players")
    ResponseEntity<?> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(players.players());
    }

    @PostMapping("/admin/players")
    ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input) {
        if (!input.isObject() || input.size() != 1 || !input.path("username").isTextual()) {
            return error(400, "bad_request", "A username is required.");
        }
        return respond(() -> players.create(jwt, input.path("username").textValue()));
    }

    @PostMapping("/admin/players/{playerId}/code")
    ResponseEntity<?> code(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID playerId) {
        return respond(() -> players.issueCode(jwt, playerId));
    }

    private ResponseEntity<?> respond(java.util.function.Supplier<PlayerProvisioning.Result> action) {
        try {
            PlayerProvisioning.Result result = action.get();
            if (result instanceof PlayerProvisioning.Result.Done done) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", done.created().id());
                body.put("username", done.created().username());
                body.putAll(code(done.created().code()));
                return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
            }
            if (result instanceof PlayerProvisioning.Result.Code issued) {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(code(issued.code()));
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

    private static Map<String, Object> code(AccountSetupCodes.Issued code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("setupCode", code.code());
        body.put("purpose", code.purpose().name());
        body.put("expiresAt", code.expiresAt().toString());
        return body;
    }
}

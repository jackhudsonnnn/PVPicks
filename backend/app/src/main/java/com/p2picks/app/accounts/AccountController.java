package com.p2picks.app.accounts;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.security.SessionCookies;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {
    private final AccountDeletionService deletion;

    AccountController(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    /**
     * Deletes the caller's account after checking their password. Not idempotency-keyed: once it succeeds the
     * session is gone, so a retry can only fail authentication.
     */
    @PostMapping("/me/delete")
    ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input) {
        if (!input.isObject() || input.size() != 1 || !input.path("password").isTextual()) {
            return error(400, "bad_request", "Your password is required.");
        }
        try {
            AccountDeletionService.Result result = deletion.delete(jwt, input.path("password").textValue());
            return switch (result.outcome()) {
                case DELETED -> ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookies.cleared())
                        .cacheControl(CacheControl.noStore()).build();
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case WRONG_PASSWORD -> error(403, "wrong_password", "That password doesn't match your account.");
                case NOT_SQUARE -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("code", "account_not_square");
                    body.put("message", "Settle up before deleting your account.");
                    body.put("blockers", result.blockers().stream()
                            .map(blocker -> blocker.name().toLowerCase(Locale.ROOT)).toList());
                    yield ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(body);
                }
            };
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}

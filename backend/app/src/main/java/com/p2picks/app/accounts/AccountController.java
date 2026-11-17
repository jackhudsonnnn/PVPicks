package com.p2picks.app.accounts;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.security.PasswordAttempts;
import com.p2picks.app.security.SessionCookies;
import jakarta.servlet.http.HttpServletRequest;
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
    private final SessionCookies cookies;
    private final PasswordAttempts attempts;

    AccountController(AccountDeletionService deletion, SessionCookies cookies, PasswordAttempts attempts) {
        this.cookies = cookies;
        this.deletion = deletion;
        this.attempts = attempts;
    }

    /**
     * Deletes the caller's account after checking their password. Not idempotency-keyed: once it succeeds the
     * session is gone, so a retry can only fail authentication. Wrong passwords share the sign-in throttle, so a
     * hijacked session can't guess the password here.
     */
    @PostMapping("/me/delete")
    ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input,
            HttpServletRequest request) {
        if (!input.isObject() || input.size() != 1 || !input.path("password").isTextual()) {
            return error(400, "bad_request", "Your password is required.");
        }
        String account = jwt.getSubject();
        String address = request.getRemoteAddr();
        if (attempts.blocked(account, address)) {
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,
                    Long.toString(attempts.window().toSeconds())).cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "rate_limited",
                            "message", "Too many wrong passwords. Wait a few minutes and try again."));
        }
        try {
            AccountDeletionService.Result result = deletion.delete(jwt, input.path("password").textValue());
            if (result.outcome() == AccountDeletionService.Outcome.WRONG_PASSWORD) attempts.failed(account, address);
            if (result.outcome() == AccountDeletionService.Outcome.DELETED) attempts.succeeded(account);
            return switch (result.outcome()) {
                case DELETED -> ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.cleared())
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

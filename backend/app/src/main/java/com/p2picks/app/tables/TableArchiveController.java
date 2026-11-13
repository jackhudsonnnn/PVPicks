package com.p2picks.app.tables;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableArchiveController {
    private final TableArchiveService archives;

    TableArchiveController(TableArchiveService archives) {
        this.archives = archives;
    }

    @GetMapping("/tables/archives")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        var result = archives.list(jwt);
        if (!result.authenticated()) return error(401, "unauthorized", "Authentication required or credentials invalid.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.archives());
    }

    @DeleteMapping("/tables/{tableId}")
    ResponseEntity<?> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a request body.");
        try {
            return switch (archives.archive(jwt, tableId, key)) {
                case ARCHIVED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can delete a table.");
                case NOT_FOUND -> error(404, "not_found", "Table not found.");
            };
        } catch (TableArchiveService.UnsettledBets exception) {
            return error(409, "table_archive_unsettled", "The table has unsettled bets.");
        } catch (TableArchiveService.MembershipChanged exception) {
            return error(409, "table_archive_membership_changed", "Membership changed; retry the request.");
        } catch (TableArchiveService.AlreadyArchived exception) {
            return error(404, "not_found", "Table not found.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

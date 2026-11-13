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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableMemberRemovalController {
    private final TableMemberRemovalService removals;

    TableMemberRemovalController(TableMemberRemovalService removals) {
        this.removals = removals;
    }

    @DeleteMapping("/tables/{tableId}/members/{userId}")
    ResponseEntity<?> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a request body.");
        try {
            return switch (removals.remove(jwt, tableId, userId, key)) {
                case REMOVED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can remove members.");
                case NOT_FOUND -> error(404, "not_found", "Table or member not found.");
            };
        } catch (TableMemberRemovalService.MissingTarget exception) {
            return error(404, "not_found", "Table or member not found.");
        } catch (TableMemberRemovalService.SelfRemoval exception) {
            return error(409, "member_removal_self", "A host cannot remove themselves.");
        } catch (TableMemberRemovalService.TargetHost exception) {
            return error(409, "member_removal_host", "Demote the host before removing them.");
        } catch (TableMemberRemovalService.UnsettledBets exception) {
            return error(409, "member_removal_unsettled", "Members cannot be removed while bets are unsettled.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

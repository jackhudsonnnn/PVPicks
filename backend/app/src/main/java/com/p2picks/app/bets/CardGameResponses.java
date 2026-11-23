package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** The HTTP side the card game controllers share: who may read a game, and how a move's outcome is answered. */
final class CardGameResponses {
    private CardGameResponses() {}

    /**
     * A game's view for a current member of its table (or a former member of an archived one); 401 for a dead
     * session, 404 for anyone else.
     */
    static ResponseEntity<?> view(JdbcTemplate jdbc, Jwt jwt, UUID betId, String mode,
            Supplier<Map<String, Object>> view) {
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
                    WHERE b.id = ? AND b.mode = ? AND (
                        (t.archived_at IS NULL AND EXISTS (SELECT 1 FROM table_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))
                        OR (t.archived_at IS NOT NULL AND EXISTS (SELECT 1 FROM table_archive_members m
                            WHERE m.table_id = t.id AND m.user_id = ?))))
                """, Boolean.class, betId, mode, userId, userId);
        if (!Boolean.TRUE.equals(visible)) return error(404, "not_found", "Bet or table not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view.get());
    }

    /** Runs a join, leave or move; the rules' refusals are 409 with {@code refusedCode} and the reason. */
    static ResponseEntity<?> run(String refusedCode, Supplier<CardGames.Outcome> call) {
        try {
            return switch (call.get()) {
                case DONE, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "The operation is not permitted.");
                case NOT_FOUND -> error(404, "not_found", "Bet or table not found.");
            };
        } catch (CardGames.Refused refused) {
            return error(409, refusedCode, refused.getMessage());
        } catch (CardGames.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "Your points limit can't cover this game's worst case.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    static ResponseEntity<Map<String, String>> badKey() {
        return error(400, "bad_request", "A valid Idempotency-Key is required.");
    }
}

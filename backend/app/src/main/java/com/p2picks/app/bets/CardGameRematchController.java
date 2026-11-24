package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** "Run it back" on a finished Jackblack or Hold'em game: start its rematch, or join the one already running. */
@RestController
class CardGameRematchController {
    private final CardGameRematch rematches;
    private final BetReadController reads;

    CardGameRematchController(CardGameRematch rematches, BetReadController reads) {
        this.rematches = rematches;
        this.reads = reads;
    }

    @PostMapping("/bets/{betId}/rematch")
    ResponseEntity<?> runItBack(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        if (!IdempotencyGuard.validKey(key)) return CardGameResponses.badKey();
        CardGameRematch.Result result;
        try {
            result = rematches.runItBack(jwt, betId, key);
        } catch (CardGames.Refused refused) {
            return error(409, "rematch_refused", refused.getMessage());
        } catch (CardGames.DebtCapExceeded | BetProposalWriter.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "Your points limit can't cover this game's worst case.");
        } catch (DataIntegrityViolationException exception) {
            // Someone else ran it back at the same moment: tapping again joins theirs.
            return error(409, "rematch_refused", "Someone just ran it back. Tap again to join.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return switch (result.outcome()) {
            case DONE, REPLAYED -> {
                ResponseEntity<?> bet = reads.get(jwt, result.betId());
                yield bet.getStatusCode().is2xxSuccessful()
                        ? ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                                .body(bet.getBody())
                        : bet;
            }
            case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
            case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            case FORBIDDEN -> error(403, "forbidden", "The operation is not permitted.");
            case NOT_FOUND -> error(404, "not_found", "Bet or table not found.");
        };
    }
}

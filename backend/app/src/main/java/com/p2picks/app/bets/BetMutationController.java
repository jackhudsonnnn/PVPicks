package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.bets.U2PickSettler;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BetMutationController {
    private static final Set<String> PROPOSAL_FIELDS =
            Set.of("modeId", "config", "question", "choices", "wagerCents", "durationSec", "forFun", "settledBy");
    private final U2PickProposalService proposals;
    private final BetPickService picks;
    private final U2PickResolutionService resolutions;
    private final BetReadController reads;

    BetMutationController(U2PickProposalService proposals, BetPickService picks,
            U2PickResolutionService resolutions, BetReadController reads) {
        this.proposals = proposals;
        this.picks = picks;
        this.resolutions = resolutions;
        this.reads = reads;
    }

    @PostMapping("/tables/{tableId}/bets")
    ResponseEntity<?> propose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        U2PickProposalService.Proposal proposal;
        try {
            proposal = parseProposal(input);
        } catch (IllegalArgumentException exception) {
            return error(400, "bad_request", "Invalid U2Pick proposal.");
        }
        try {
            var result = proposals.create(jwt, tableId, proposal, key);
            return switch (result.outcome()) {
                case CREATED, REPLAYED -> {
                    ResponseEntity<?> bet = reads.get(jwt, result.betId());
                    yield bet.getStatusCode().is2xxSuccessful()
                            ? ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(bet.getBody())
                            : bet;
                }
                case CONFLICT -> conflict();
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> notFound();
            };
        } catch (IllegalArgumentException exception) {
            return error(400, "bad_request", "Invalid U2Pick proposal.");
        } catch (U2PickProposalService.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "This proposal would exceed the debt cap.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    @PutMapping("/bets/{betId}/pick")
    ResponseEntity<?> putPick(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        if (!input.isObject() || input.size() != 1 || !input.path("choice").isTextual()) {
            return error(400, "bad_request", "A valid choice is required.");
        }
        return changePick(jwt, betId, input.path("choice").textValue(), key);
    }

    @DeleteMapping("/bets/{betId}/pick")
    ResponseEntity<?> clearPick(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a body.");
        return changePick(jwt, betId, null, key);
    }

    @PostMapping("/bets/{betId}/settle")
    ResponseEntity<?> resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return badKey();
        JsonNode choice = input.path("winningChoice");
        if (!input.isObject() || input.size() != 1 || (!choice.isTextual() && !choice.isNull())) {
            return error(400, "bad_request", "A winning choice or null is required.");
        }
        try {
            return switch (resolutions.resolve(jwt, betId, choice.isNull() ? null : choice.textValue(), key)) {
                case RESOLVED, REPLAYED -> noContent();
                case CONFLICT -> conflict();
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> notFound();
            };
        } catch (U2PickResolutionService.InvalidChoice exception) {
            return error(400, "invalid_choice", "The winning choice does not match the bet.");
        } catch (U2PickResolutionService.Closed exception) {
            return error(409, "bet_not_pending", "The bet is not awaiting a result.");
        } catch (U2PickResolutionService.MembersChanged exception) {
            return error(409, "bet_affected_users_changed", "Affected users changed; retry the request.");
        } catch (U2PickResolutionService.CopyPropagationUnavailable exception) {
            return error(409, "quick_pick_propagation_unavailable", "Quick Pick result propagation is unavailable.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    private ResponseEntity<?> changePick(Jwt jwt, UUID betId, String choice, String key) {
        try {
            return switch (picks.change(jwt, betId, choice, key)) {
                case UPDATED, REPLAYED -> noContent();
                case CONFLICT -> conflict();
                case UNAUTHORIZED -> unauthorized();
                case FORBIDDEN -> forbidden();
                case NOT_FOUND -> notFound();
            };
        } catch (BetPickService.InvalidChoice exception) {
            return error(400, "invalid_choice", "The choice does not match the bet.");
        } catch (BetPickService.Closed exception) {
            return error(409, "bet_closed", "The pick window has closed.");
        } catch (BetPickService.DebtCapExceeded exception) {
            return error(409, "debt_cap_exceeded", "This pick would exceed the debt cap.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return unauthorized();
        }
    }

    private static U2PickProposalService.Proposal parseProposal(JsonNode input) {
        if (!input.isObject() || !onlyFields(input, PROPOSAL_FIELDS)
                || !input.path("modeId").isTextual() || !input.path("modeId").textValue().equals("u2pick")
                || !(input.get("config") instanceof ObjectNode config)
                || !input.path("question").isTextual() || !input.path("choices").isArray()
                || !input.path("wagerCents").isIntegralNumber() || !input.path("wagerCents").canConvertToLong()
                || !input.path("durationSec").isIntegralNumber() || !input.path("durationSec").canConvertToInt()
                || !input.path("forFun").isBoolean()) {
            throw new IllegalArgumentException("Invalid proposal");
        }
        List<String> choices = new ArrayList<>();
        for (JsonNode choice : input.path("choices")) {
            if (!choice.isTextual()) throw new IllegalArgumentException("Invalid choice");
            choices.add(choice.textValue());
        }
        U2PickSettler.Rule settler = null;
        if (input.has("settledBy")) {
            if (!input.path("settledBy").isTextual()) throw new IllegalArgumentException("Invalid settler");
            settler = switch (input.path("settledBy").textValue()) {
                case "anyone" -> U2PickSettler.Rule.ANYONE;
                case "non_participants" -> U2PickSettler.Rule.NON_PARTICIPANTS;
                case "hosts" -> U2PickSettler.Rule.HOSTS;
                case "me" -> U2PickSettler.Rule.ME;
                default -> throw new IllegalArgumentException("Invalid settler");
            };
        }
        return new U2PickProposalService.Proposal(new U2PickDefinition(input.path("question").textValue(), choices),
                config, input.path("wagerCents").longValue(), input.path("durationSec").intValue(),
                input.path("forFun").booleanValue(), settler);
    }

    private static boolean onlyFields(JsonNode input, Set<String> allowed) {
        var fields = input.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) return false;
        return true;
    }

    private static ResponseEntity<?> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Map<String, String>> badKey() {
        return error(400, "bad_request", "A valid Idempotency-Key is required.");
    }

    private static ResponseEntity<Map<String, String>> conflict() {
        return error(409, "conflict", "Idempotency key was used for a different request.");
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return error(403, "forbidden", "The operation is not permitted.");
    }

    private static ResponseEntity<Map<String, String>> notFound() {
        return error(404, "not_found", "Bet or table not found.");
    }
}

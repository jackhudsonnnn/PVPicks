package com.p2picks.app.bets;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ModeController {
    /** Every mode the product knows; the client describes them, the server says which accept proposals. */
    static final List<String> ALL = List.of("u2pick", "total_disaster", "give_and_take", "either_or",
            "prop_hunt", "king_of_the_hill", "choose_their_fate", "scorcerer");

    record Mode(String id, boolean available) {}

    /** Which bet modes can be proposed now. Later this depends on each league's data plan (ADR 0005). */
    @GetMapping("/modes")
    ResponseEntity<List<Mode>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ALL.stream()
                .map(id -> new Mode(id, id.equals("u2pick") || SportsProposalService.MODES.contains(id)))
                .toList());
    }
}

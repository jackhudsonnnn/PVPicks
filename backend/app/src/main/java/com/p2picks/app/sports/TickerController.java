package com.p2picks.app.sports;

import com.p2picks.app.common.ApiErrors;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TickerController {
    private final TickerService ticker;

    TickerController(TickerService ticker) {
        this.ticker = ticker;
    }

    @GetMapping("/ticker")
    ResponseEntity<?> ticker() {
        return ticker.ticker()
                .<ResponseEntity<?>>map(games -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(Map.of("games", games)))
                .orElseGet(() -> ApiErrors.error(503, "feed_unavailable", "Live scores are unavailable right now."));
    }
}

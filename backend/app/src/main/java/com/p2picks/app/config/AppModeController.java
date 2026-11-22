package com.p2picks.app.config;

import com.p2picks.app.common.AppMode;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /config}: the backend's {@link AppMode}, signed in or out, so the client picks the right sign-in (the
 * demo picker in dev, sign-in codes on LAN) without guessing from which endpoints exist.
 */
@RestController
class AppModeController {
    private final AppMode mode;

    AppModeController(AppMode mode) {
        this.mode = mode;
    }

    @GetMapping("/config")
    ResponseEntity<Map<String, String>> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("mode", mode.id()));
    }
}

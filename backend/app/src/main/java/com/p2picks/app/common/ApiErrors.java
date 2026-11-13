package com.p2picks.app.common;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Builds the API's uncacheable {@code {code, message}} error responses. */
public final class ApiErrors {
    private ApiErrors() {}

    public static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}

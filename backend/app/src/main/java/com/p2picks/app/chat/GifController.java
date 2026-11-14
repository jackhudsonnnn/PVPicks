package com.p2picks.app.chat;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GifController {
    private final GifCatalog gifs;

    GifController(GifCatalog gifs) {
        this.gifs = gifs;
    }

    /** The GIF picker's library. It changes only with a deploy, so clients may cache it for an hour. */
    @GetMapping("/gifs")
    ResponseEntity<GifCatalog.Catalog> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(gifs.catalog());
    }
}

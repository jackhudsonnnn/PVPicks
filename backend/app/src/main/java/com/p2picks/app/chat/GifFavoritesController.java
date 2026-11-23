package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your starred GIFs (domain rules, "Chat"): a shortlist from the library that the picker shows first, kept with your
 * account so it follows you between devices. Starring and unstarring set it, so repeating either changes nothing
 * (no Idempotency-Key needed). At most {@link #MAX} stars; only GIFs in the library.
 */
@RestController
class GifFavoritesController {
    static final int MAX = 100;
    private final JdbcTemplate jdbc;
    private final GifCatalog gifs;
    private final MutationAuthentication authentication;
    private final TransactionTemplate transactions;

    GifFavoritesController(JdbcTemplate jdbc, GifCatalog gifs, MutationAuthentication authentication,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.gifs = gifs;
        this.authentication = authentication;
        this.transactions = transactions;
    }

    /** Newest star first; stars for GIFs no longer in the library are left out. */
    @GetMapping("/me/gif-favorites")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Boolean session = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                      AND u.status = 'ACTIVE')
                """, Boolean.class, UUID.fromString(jwt.getId()), userId);
        if (!Boolean.TRUE.equals(session)) return unauthorized();
        List<String> ids = jdbc.queryForList("""
                SELECT gif_id FROM gif_favorites WHERE user_id = ? ORDER BY starred_at DESC, gif_id
                """, String.class, userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("gifIds", ids.stream().filter(gifs::contains).toList()));
    }

    @PutMapping("/me/gif-favorites/{gifId}")
    ResponseEntity<?> star(@AuthenticationPrincipal Jwt jwt, @PathVariable String gifId) {
        if (!gifs.contains(gifId)) return error(404, "not_found", "That GIF isn't in the library.");
        return change(jwt, gifId, true);
    }

    @DeleteMapping("/me/gif-favorites/{gifId}")
    ResponseEntity<?> unstar(@AuthenticationPrincipal Jwt jwt, @PathVariable String gifId) {
        if (!gifs.contains(gifId)) return error(404, "not_found", "That GIF isn't in the library.");
        return change(jwt, gifId, false);
    }

    private enum Outcome { DONE, UNAUTHORIZED, FULL }

    private ResponseEntity<?> change(Jwt jwt, String gifId, boolean star) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> {
                // Locks the account, so two stars at once can't pass the cap together.
                if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
                authentication.requireUnexpired(jwt);
                if (!star) {
                    jdbc.update("DELETE FROM gif_favorites WHERE user_id = ? AND gif_id = ?", userId, gifId);
                    return Outcome.DONE;
                }
                Integer count = jdbc.queryForObject("SELECT count(*) FROM gif_favorites WHERE user_id = ?",
                        Integer.class, userId);
                boolean already = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM gif_favorites WHERE user_id = ? AND gif_id = ?)
                        """, Boolean.class, userId, gifId));
                if (!already && count != null && count >= MAX) return Outcome.FULL;
                jdbc.update("""
                        INSERT INTO gif_favorites (user_id, gif_id) VALUES (?, ?) ON CONFLICT DO NOTHING
                        """, userId, gifId);
                return Outcome.DONE;
            });
        } catch (MutationAuthentication.ExpiredSession expired) {
            return unauthorized();
        }
        if (outcome == Outcome.DONE) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        if (outcome == Outcome.FULL) {
            return error(409, "favorites_full", "You can star up to " + MAX + " GIFs. Unstar one to make room.");
        }
        return unauthorized();
    }

    private static ResponseEntity<?> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }
}

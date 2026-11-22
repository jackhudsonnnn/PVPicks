package com.p2picks.app.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wrong sign-in codes, for admins: which players are close to (or in) their lockout, and clearing it. Ten wrong codes
 * against one player lock that player (QR included) for the window; anyone who knows a username can cause that, so
 * the admin can unlock them at once. The address backstop is shared by the whole party behind Docker Desktop (docs:
 * LAN watch party). Admin only, under {@code /admin/**}.
 */
@RestController
class SignInLockoutController {
    private static final Logger log = LoggerFactory.getLogger(SignInLockoutController.class);

    record Counter(int wrongCodes, boolean locked, Instant resetsAt) {}

    record Player(UUID id, String username, Integer hue, int wrongCodes, boolean locked, Instant resetsAt) {}

    record Lockouts(int playerLimit, int addressLimit, Counter address, List<Player> players) {}

    private final LoginThrottle throttle;
    private final JdbcTemplate jdbc;

    SignInLockoutController(LoginThrottle throttle, JdbcTemplate jdbc) {
        this.throttle = throttle;
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/sign-in-lockout")
    ResponseEntity<?> status() {
        LoginThrottle.CodeLockouts lockouts;
        try {
            lockouts = throttle.codeLockouts();
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
        Map<UUID, Object[]> names = new HashMap<>();
        List<UUID> ids = lockouts.players().stream().map(LoginThrottle.PlayerCounter::player).toList();
        if (!ids.isEmpty()) {
            jdbc.query("SELECT id, username::text AS username, hue FROM users WHERE id = ANY (?)",
                    rs -> {
                        names.put(rs.getObject("id", UUID.class), new Object[] {rs.getString("username"),
                                rs.getObject("hue", Integer.class)});
                    }, (Object) ids.toArray(UUID[]::new));
        }
        List<Player> players = lockouts.players().stream()
                .filter(p -> names.containsKey(p.player()))
                .map(p -> {
                    Object[] name = names.get(p.player());
                    return new Player(p.player(), (String) name[0], (Integer) name[1], p.counter().wrongCodes(),
                            p.counter().locked(), p.counter().resetsAt());
                }).toList();
        LoginThrottle.CodeCounter address = lockouts.address();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Lockouts(lockouts.playerLimit(),
                lockouts.addressLimit(), new Counter(address.wrongCodes(), address.locked(), address.resetsAt()),
                players));
    }

    /** Clears every wrong-code counter: all players, unknown names and the address backstop. */
    @DeleteMapping("/admin/sign-in-lockout")
    ResponseEntity<?> clearAll(@AuthenticationPrincipal Jwt jwt) {
        try {
            int cleared = throttle.clearCodeLockouts();
            log.info("Admin {} cleared every sign-in code lockout ({} counters)", jwt.getSubject(), cleared);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    /** Clears one player's wrong codes, so they can sign in straight away. */
    @DeleteMapping("/admin/sign-in-lockout/players/{playerId}")
    ResponseEntity<?> clearPlayer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID playerId) {
        try {
            throttle.clearCodeLockout(playerId);
            log.info("Admin {} cleared the sign-in code lockout of {}", jwt.getSubject(), playerId);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (DataAccessException unavailable) {
            return unavailable();
        }
    }

    /** Redis is down: the throttle is failing open, so there's nothing to show or clear. */
    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "unavailable", "message", "The sign-in throttle (Redis) is unavailable."));
    }
}

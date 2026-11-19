package com.p2picks.app.security;

import com.p2picks.app.common.PostgresText;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each player's sign-in code: {@value #LENGTH} characters from {@link #ALPHABET} (no look-alikes such as 0/O or
 * 1/I/L), typed on the sign-in page or opened from the QR link {@code /signin#CODE}. Players have no passwords
 * (owner decision, 2026-11-17). The code stays the same until an admin rotates it, which also ends every session
 * of that player.
 *
 * <p>Short codes rely on the sign-in throttle ({@link LoginThrottle#blockedCode}). Codes are stored as an HMAC for
 * lookup and AES-GCM encrypted for the admin's view, with keys derived from the session secret; changing that
 * secret makes every code unreadable, and {@link #issueMissing} then gives each player a new one.
 */
@Component
public class SignInCodes {
    public static final int LENGTH = 4;
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    /** Marker password hash for players: no password matches it. */
    public static final String NO_PASSWORD = "!code-only";
    private static final Logger log = LoggerFactory.getLogger(SignInCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final byte[] lookupKey;
    private final SecretKeySpec sealKey;

    public SignInCodes(JdbcTemplate jdbc, @Value("${PVPICKS_DEV_JWT_SECRET}") String secret) {
        this.jdbc = jdbc;
        byte[] root = Base64.getDecoder().decode(secret);
        this.lookupKey = hmac(root, "p2picks sign-in code lookup");
        this.sealKey = new SecretKeySpec(hmac(root, "p2picks sign-in code seal"), "AES");
    }

    /** The code as typed: case, spaces and dashes don't matter. Null when it can't be a code. */
    public static String normalize(String typed) {
        if (typed == null) return null;
        String code = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != LENGTH) return null;
        for (char c : code.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        return code;
    }

    /**
     * Gives the player a new code, replacing any old one, and ends all their sessions so only the new code gets them
     * back in. Returns the code.
     */
    @Transactional
    public String rotate(UUID playerId, UUID rotatedBy) {
        String code = store(playerId, rotatedBy);
        jdbc.update("""
                UPDATE auth_sessions SET revoked_at = greatest(clock_timestamp(), created_at)
                WHERE user_id = ? AND revoked_at IS NULL
                """, playerId);
        return code;
    }

    /**
     * The player's code, giving them one first if they have none (or it can't be read). Unlike {@link #rotate}, this
     * signs nobody out: a player without a code had no way in with one.
     */
    @Transactional
    public String ensure(UUID playerId) {
        return reveal(playerId).orElseGet(() -> store(playerId, null));
    }

    /** Draws a free code and stores it for the player, replacing any old one. */
    private String store(UUID playerId, UUID rotatedBy) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = generate();
            byte[] lookup = lookup(code);
            // Another player has this code (or it's the current one): draw again. A failed insert would abort the
            // transaction, so check first; the unique index still guards a race.
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM sign_in_codes WHERE lookup = ?)", Boolean.class, (Object) lookup))) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO sign_in_codes (user_id, lookup, sealed, rotated_by) VALUES (?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET lookup = EXCLUDED.lookup, sealed = EXCLUDED.sealed,
                        rotated_at = now(), rotated_by = EXCLUDED.rotated_by
                    """, playerId, lookup, seal(code), rotatedBy);
            return code;
        }
        throw new IllegalStateException("No free sign-in code after 20 draws");
    }

    /** The player's current code, for the admin; empty when they have none (or it was sealed with an old secret). */
    public Optional<String> reveal(UUID playerId) {
        List<byte[]> sealed = jdbc.query("SELECT sealed FROM sign_in_codes WHERE user_id = ?",
                (rs, row) -> rs.getBytes("sealed"), playerId);
        if (sealed.isEmpty()) return Optional.empty();
        try {
            return Optional.of(open(sealed.getFirst()));
        } catch (GeneralSecurityException unreadable) {
            log.warn("Sign-in code for {} can't be read (session secret changed?)", playerId);
            return Optional.empty();
        }
    }

    /** The player with this username (case-insensitive, trimmed), for typed sign-in; empty when there's none. */
    Optional<UUID> playerNamed(String username) {
        if (username == null) return Optional.empty();
        String name = PostgresText.trim(username).toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) return Optional.empty();
        return jdbc.query("SELECT id FROM users WHERE username = ? AND role = 'PLAYER' AND status <> 'DELETED'",
                (rs, row) -> rs.getObject("id", UUID.class), name).stream().findFirst();
    }

    /** Whether the typed code (case, spaces and dashes ignored) is this player's current code. */
    boolean matches(UUID playerId, String typed) {
        String code = normalize(typed);
        if (code == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sign_in_codes WHERE user_id = ? AND lookup = ?)", Boolean.class,
                playerId, (Object) lookup(code)));
    }

    /** Removes a deleted player's code. */
    @Transactional
    public void forget(UUID playerId) {
        jdbc.update("DELETE FROM sign_in_codes WHERE user_id = ?", playerId);
    }

    /**
     * Gives every active or frozen player without a readable code a new one: on start (players created before codes
     * existed, or after the secret changed) and after a demo reset. Returns how many were issued.
     */
    @Transactional
    public int issueMissing() {
        List<UUID> players = jdbc.queryForList("""
                SELECT u.id FROM users u LEFT JOIN sign_in_codes c ON c.user_id = u.id
                WHERE u.role = 'PLAYER' AND u.status IN ('ACTIVE', 'FROZEN') AND c.user_id IS NULL
                """, UUID.class);
        List<UUID> unreadable = jdbc.queryForList("SELECT user_id FROM sign_in_codes", UUID.class).stream()
                .filter(id -> reveal(id).isEmpty()).toList();
        for (UUID player : players) store(player, null);
        for (UUID player : unreadable) store(player, null);
        return players.size() + unreadable.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    void issueMissingOnStart() {
        int issued = issueMissing();
        if (issued > 0) log.info("Issued sign-in codes to {} players", issued);
    }

    private static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return code.toString();
    }

    private byte[] lookup(String code) {
        return hmac(lookupKey, code);
    }

    private byte[] seal(String code) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealKey, new GCMParameterSpec(128, nonce));
            byte[] text = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[nonce.length + text.length];
            System.arraycopy(nonce, 0, sealed, 0, nonce.length);
            System.arraycopy(text, 0, sealed, nonce.length, text.length);
            return sealed;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot seal a sign-in code", exception);
        }
    }

    private String open(byte[] sealed) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, sealKey, new GCMParameterSpec(128, sealed, 0, 12));
        return new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

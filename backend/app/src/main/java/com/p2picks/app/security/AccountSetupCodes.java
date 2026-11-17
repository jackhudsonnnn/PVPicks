package com.p2picks.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use codes that let a player choose their own password (LAN plan, "Accounts and passwords"). An admin issues a
 * SETUP code for a new account or a RESET code for a forgotten password; the player redeems it at
 * {@code /setup#<code>}. Codes are 128 random bits, valid for 24 hours, stored only as a SHA-256 hash; issuing a new
 * one deletes the user's unused ones.
 */
@Component
public class AccountSetupCodes {
    public static final Duration LIFETIME = Duration.ofHours(24);

    public enum Purpose { SETUP, RESET }

    /** A freshly issued code: shown once to the admin, never stored. */
    public record Issued(String code, Purpose purpose, Instant expiresAt) {}

    /** The account a valid code belongs to. */
    record Holder(UUID codeId, UUID userId, String username) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    AccountSetupCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored password hash that no password matches, for an account waiting for its setup code. */
    public static String unusablePasswordHash() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "!unset:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Issues a code for the user, replacing any unused one. The caller locks the user. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(UUID userId, Purpose purpose, UUID issuedBy) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(LIFETIME);
        jdbc.update("DELETE FROM account_setup_codes WHERE user_id = ? AND used_at IS NULL", userId);
        jdbc.update("""
                INSERT INTO account_setup_codes (id, user_id, code_hash, purpose, issued_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, hash(code), purpose.name(), issuedBy, Timestamp.from(now),
                Timestamp.from(expiresAt));
        return new Issued(code, purpose, expiresAt);
    }

    /** Locks and returns the unused, unexpired code's active account, or empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Holder> lockValid(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{22}")) return Optional.empty();
        List<Holder> holders = jdbc.query("""
                SELECT c.id, u.id AS user_id, u.username::text AS username
                FROM account_setup_codes c JOIN users u ON u.id = c.user_id
                WHERE c.code_hash = ? AND c.used_at IS NULL AND c.expires_at > clock_timestamp()
                  AND u.status = 'ACTIVE'
                FOR UPDATE OF c, u
                """, (rs, row) -> new Holder(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("username")), (Object) hash(code));
        return holders.isEmpty() ? Optional.empty() : Optional.of(holders.getFirst());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markUsed(UUID codeId) {
        jdbc.update("UPDATE account_setup_codes SET used_at = clock_timestamp() WHERE id = ? AND used_at IS NULL",
                codeId);
    }

    private static byte[] hash(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

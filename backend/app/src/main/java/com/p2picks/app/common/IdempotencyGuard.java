package com.p2picks.app.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotencyGuard {
    public enum Claim { NEW, REPLAY, CONFLICT }
    public enum SuccessStatus {
        OK(200), CREATED(201), NO_CONTENT(204);

        private final int code;

        SuccessStatus(int code) {
            this.code = code;
        }
    }

    public static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.codePointCount(0, key.length()) <= 255;
    }

    private final JdbcTemplate jdbc;
    private final SecretKeySpec secret;

    public IdempotencyGuard(JdbcTemplate jdbc, @Value("${PVPICKS_DEV_IDEMPOTENCY_SECRET}") String encoded) {
        this.jdbc = jdbc;
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("PVPICKS_DEV_IDEMPOTENCY_SECRET must encode at least 32 bytes");
        }
        secret = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(UUID userId, String key, String operation, String canonicalRequest) {
        return claim(userId, key, operation, canonicalRequest, SuccessStatus.NO_CONTENT);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(
            UUID userId, String key, String operation, String canonicalRequest, SuccessStatus responseStatus) {
        byte[] verifier = digest("p2picks-dev-idempotency-key-verifier-v1");
        jdbc.update("""
                INSERT INTO http_idempotency_key_guard (singleton, key_verifier)
                VALUES (true, ?) ON CONFLICT (singleton) DO NOTHING
                """, verifier);
        byte[] storedVerifier = jdbc.queryForObject("""
                SELECT key_verifier FROM http_idempotency_key_guard WHERE singleton = true
                """, byte[].class);
        if (storedVerifier == null || !MessageDigest.isEqual(verifier, storedVerifier)) {
            throw new IllegalStateException("Idempotency key changed; restore the original key");
        }
        byte[] keyDigest = digest("key\0" + key);
        byte[] requestDigest = digest("request\0" + operation + "\0" + canonicalRequest);
        int inserted = jdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, key_digest) DO NOTHING
                """, userId, keyDigest, requestDigest, operation, responseStatus.code);
        if (inserted == 1) {
            return Claim.NEW;
        }
        List<StoredRequest> stored = jdbc.query("""
                SELECT operation, request_digest, response_status FROM http_idempotency
                WHERE user_id = ? AND key_digest = ?
                """, (rs, row) -> new StoredRequest(
                        rs.getString("operation"), rs.getBytes("request_digest"), rs.getInt("response_status")),
                userId, keyDigest);
        if (stored.size() != 1) {
            throw new IllegalStateException("Idempotency record disappeared during transaction");
        }
        StoredRequest prior = stored.getFirst();
        if (!prior.operation().equals(operation) || !MessageDigest.isEqual(prior.digest(), requestDigest)) {
            return Claim.CONFLICT;
        }
        if (prior.status() != responseStatus.code) {
            throw new IllegalStateException("Idempotency response status changed for an existing operation");
        }
        return Claim.REPLAY;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void discardNewClaim(UUID userId, String key) {
        int deleted = jdbc.update("DELETE FROM http_idempotency WHERE user_id = ? AND key_digest = ?",
                userId, digest("key\0" + key));
        if (deleted != 1) {
            throw new IllegalStateException("New idempotency claim disappeared");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveResponseReference(UUID userId, String key, String operation, UUID resourceId) {
        int updated = jdbc.update("""
                UPDATE http_idempotency SET response_resource_id = ?
                WHERE user_id = ? AND key_digest = ? AND operation = ? AND response_resource_id IS NULL
                """, resourceId, userId, digest("key\0" + key), operation);
        if (updated != 1) throw new IllegalStateException("Cannot save idempotency response reference");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID responseReference(UUID userId, String key, String operation) {
        List<UUID> references = jdbc.query("""
                SELECT response_resource_id FROM http_idempotency
                WHERE user_id = ? AND key_digest = ? AND operation = ?
                """, (rs, row) -> rs.getObject("response_resource_id", UUID.class),
                userId, digest("key\0" + key), operation);
        if (references.size() != 1 || references.getFirst() == null) {
            throw new IllegalStateException("Idempotency response reference is missing");
        }
        return references.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveResponseData(UUID userId, String key, String operation, String data) {
        int updated = jdbc.update("""
                UPDATE http_idempotency SET response_data = ?::jsonb
                WHERE user_id = ? AND key_digest = ? AND operation = ? AND response_data IS NULL
                """, data, userId, digest("key\0" + key), operation);
        if (updated != 1) throw new IllegalStateException("Cannot save idempotency response data");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String responseData(UUID userId, String key, String operation) {
        List<String> data = jdbc.query("""
                SELECT response_data::text FROM http_idempotency
                WHERE user_id = ? AND key_digest = ? AND operation = ?
                """, (rs, row) -> rs.getString(1), userId, digest("key\0" + key), operation);
        if (data.size() != 1 || data.getFirst() == null) {
            throw new IllegalStateException("Idempotency response data is missing");
        }
        return data.getFirst();
    }

    private byte[] digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot compute idempotency fingerprint", exception);
        }
    }

    private record StoredRequest(String operation, byte[] digest, int status) {}
}

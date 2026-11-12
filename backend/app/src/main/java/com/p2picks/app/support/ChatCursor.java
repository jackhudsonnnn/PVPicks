package com.p2picks.app;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ChatCursor {
    static class InvalidCursor extends RuntimeException {}

    private static final int NONCE_BYTES = 12;
    private static final int PAYLOAD_BYTES = 41;
    private static final int TOKEN_BYTES = NONCE_BYTES + PAYLOAD_BYTES + 16;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    ChatCursor(@Value("${PVPICKS_DEV_IDEMPOTENCY_SECRET}") String encoded) {
        byte[] secret = Base64.getDecoder().decode(encoded);
        if (secret.length < 32) throw new IllegalArgumentException("Idempotency secret must encode at least 32 bytes");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            key = new SecretKeySpec(mac.doFinal("p2picks-chat-cursor-v1".getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot derive chat cursor key", exception);
        }
    }

    String encode(UUID userId, UUID tableId, long position) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES).put((byte) 1);
        putUuid(payload, userId);
        putUuid(payload, tableId);
        payload.putLong(position);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(payload.array());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(TOKEN_BYTES).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot encode chat cursor", exception);
        }
    }

    long decode(String token, UUID userId, UUID tableId) {
        byte[] bytes;
        try {
            if (token.length() != 92) throw new InvalidCursor();
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCursor();
        }
        if (bytes.length != TOKEN_BYTES
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) {
            throw new InvalidCursor();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, NONCE_BYTES));
            ByteBuffer payload = ByteBuffer.wrap(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES));
            if (payload.get() != 1 || !readUuid(payload).equals(userId) || !readUuid(payload).equals(tableId)) {
                throw new InvalidCursor();
            }
            long position = payload.getLong();
            if (position <= 0) throw new InvalidCursor();
            return position;
        } catch (AEADBadTagException exception) {
            throw new InvalidCursor();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot decode chat cursor", exception);
        }
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}

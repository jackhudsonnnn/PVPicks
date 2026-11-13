package com.p2picks.app.security;

import com.p2picks.app.common.PostgresText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DevelopmentSessions {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String dummyHash;

    DevelopmentSessions(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder, JwtDecoder decoder) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        this.decoder = decoder;
        this.dummyHash = passwords.encode("nonexistent-account");
    }

    @Transactional
    public String login(String username, String password, String previousToken) {
        String normalized = PostgresText.trim(username).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]{3,15}")
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return null;
        }
        List<Account> accounts = jdbc.query("""
                SELECT id, password_hash FROM users WHERE username = ?::citext AND status = 'ACTIVE' FOR UPDATE
                """, (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("password_hash")), normalized);
        Account account = accounts.isEmpty() ? null : accounts.getFirst();
        boolean matches = passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        if (!matches || account == null) {
            return null;
        }

        if (previousToken != null) {
            try {
                Jwt previous = decoder.decode(previousToken);
                revoke(UUID.fromString(previous.getId()));
            } catch (JwtException exception) {
                // An invalid or expired old cookie cannot authenticate or be revoked.
            }
        }

        UUID sessionId = UUID.randomUUID();
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(8, ChronoUnit.HOURS);
        jdbc.update("INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sessionId, account.id(), java.sql.Timestamp.from(issuedAt), java.sql.Timestamp.from(expiresAt));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(SessionSecurity.ISSUER)
                .subject(account.id().toString()).id(sessionId.toString())
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    record Account(UUID id, String passwordHash) {}
}

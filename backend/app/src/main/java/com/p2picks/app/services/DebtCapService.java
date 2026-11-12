package com.p2picks.app;

import java.math.BigInteger;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DebtCapService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED }
    static class OutstandingDebt extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;

    DebtCapService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            DebtQuery debt) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
    }

    @Transactional
    public Outcome replace(Jwt jwt, String key, Integer capCents) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) {
            return Outcome.UNAUTHORIZED;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "debt-cap.replace.v1",
                capCents == null ? "off" : capCents.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        BigInteger currentDebt = debt.read(jwt).orElseThrow(MutationAuthentication.ExpiredSession::new);
        authentication.requireUnexpired(jwt);
        if (currentDebt.signum() != 0) throw new OutstandingDebt();
        jdbc.update("""
                INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET cap_cents = EXCLUDED.cap_cents,
                    updated_at = clock_timestamp()
                """, userId, capCents);
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}

package com.p2picks.app.common;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window request limits in Redis, shared by every instance (Kafka & Redis, item 4). Each call counts one use of
 * a bucket for a subject (an account, or an address before sign-in). Redis is disposable (ADR 0003): when it's
 * unreachable the limiter allows the request and logs, so an outage relaxes limits rather than blocking players.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    /** Counts the use and starts the window on the first one; returns the count and the window's remaining ms. */
    private static final RedisScript<List> COUNT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return {n, redis.call('PTTL', KEYS[1])}
            """, List.class);

    /** Whether the request may go ahead, and if not, how many seconds until the window resets. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final StringRedisTemplate redis;

    RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision acquire(String bucket, String subject, int limit, Duration window) {
        try {
            List<?> result = redis.execute(COUNT, List.of("rate:" + bucket + ":" + subject),
                    Long.toString(window.toMillis()));
            long count = ((Number) result.get(0)).longValue();
            if (count <= limit) return Decision.ALLOWED;
            long remaining = Math.max(((Number) result.get(1)).longValue(), 0);
            return new Decision(false, Math.max(1, (remaining + 999) / 1000));
        } catch (DataAccessException | NullPointerException | ClassCastException exception) {
            log.warn("Rate limiter unavailable; allowing the request: {}", exception.getMessage());
            return Decision.ALLOWED;
        }
    }
}

package com.p2picks.app.sports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Caches the ticker in Redis. Every signed-in client refetches it on each {@code ticker.changed} invalidation, so
 * without a cache one feed change becomes one identical query per connected client.
 *
 * <p>Entries live under a generation number that {@link GameSync} bumps after each committed change. A reader that
 * raced a commit can only write under the old generation, which nobody reads again, so a client that refetches after
 * an invalidation never gets the previous ticker. The short TTL covers what changes with time alone: feed freshness
 * (the 503) and the New York date. Redis is disposable (ADR 0003): any Redis error falls back to PostgreSQL.
 */
@Component
class TickerCache {
    private static final Logger log = LoggerFactory.getLogger(TickerCache.class);
    private static final String GENERATION = "ticker:v1:generation";
    private static final TypeReference<List<TickerService.TickerGame>> GAMES = new TypeReference<>() {};
    /** Stored for an unavailable feed, which isn't a valid games list. */
    private static final String UNAVAILABLE = "unavailable";

    private final TickerService ticker;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    TickerCache(TickerService ticker, StringRedisTemplate redis, ObjectMapper json,
            @Value("${pvpicks.sports.ticker-cache-ttl:PT5S}") Duration ttl) {
        this.ticker = ticker;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    Optional<List<TickerService.TickerGame>> ticker() {
        String key;
        try {
            String generation = redis.opsForValue().get(GENERATION);
            key = "ticker:v1:" + (generation == null ? "0" : generation);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return cached.equals(UNAVAILABLE) ? Optional.empty() : Optional.of(json.readValue(cached, GAMES));
            }
        } catch (DataAccessException | JsonProcessingException exception) {
            log.debug("Ticker cache unavailable; reading PostgreSQL", exception);
            return ticker.ticker();
        }
        Optional<List<TickerService.TickerGame>> fresh = ticker.ticker();
        try {
            redis.opsForValue().set(key, fresh.isEmpty() ? UNAVAILABLE : json.writeValueAsString(fresh.get()), ttl);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.debug("Could not cache the ticker", exception);
        }
        return fresh;
    }

    /** Starts a new generation once the current transaction commits (immediately when there is none). */
    void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    void invalidate() {
        try {
            redis.opsForValue().increment(GENERATION);
        } catch (DataAccessException exception) {
            // Entries expire on their own, so the ticker is at most one TTL behind.
            log.warn("Could not invalidate the ticker cache: {}", exception.getMessage());
        }
    }
}

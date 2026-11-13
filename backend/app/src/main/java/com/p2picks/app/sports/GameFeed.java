package com.p2picks.app.sports;

import java.time.LocalDate;
import java.util.List;

/** A source of canonical game snapshots (ADR 0004). */
public interface GameFeed {
    /** Games scheduled on the given America/New_York calendar date. */
    List<FeedGame> gamesOn(League league, LocalDate date) throws FeedUnavailable;

    /** The feed could not be read; it must never be treated as "no games". */
    class FeedUnavailable extends Exception {
        private final boolean rateLimited;

        public FeedUnavailable(String message, boolean rateLimited, Throwable cause) {
            super(message, cause);
            this.rateLimited = rateLimited;
        }

        public boolean rateLimited() {
            return rateLimited;
        }
    }
}

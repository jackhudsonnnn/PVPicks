package com.p2picks.app.sports;

import java.time.LocalDate;
import java.util.List;

/** A source of canonical game snapshots (ADR 0004). */
public interface GameFeed {
    /**
     * Games the provider lists for any of the given dates. Providers may date late games by UTC or by local time,
     * so callers ask for neighbouring dates and place games by their own start time.
     */
    List<FeedGame> games(League league, List<LocalDate> dates) throws FeedUnavailable;

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

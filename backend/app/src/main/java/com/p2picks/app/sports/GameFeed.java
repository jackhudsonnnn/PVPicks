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
        /** The provider's HTTP status, or 0 when it didn't answer. */
        private final int status;

        public FeedUnavailable(String message, boolean rateLimited, Throwable cause) {
            this(message, rateLimited, cause, 0);
        }

        public FeedUnavailable(String message, boolean rateLimited, Throwable cause, int status) {
            super(message, cause);
            this.rateLimited = rateLimited;
            this.status = status;
        }

        public boolean rateLimited() {
            return rateLimited;
        }

        /** The plan doesn't include this endpoint (401 or 403). */
        public boolean notInPlan() {
            return status == 401 || status == 403;
        }
    }
}

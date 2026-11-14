-- ADR 0005: keep what the feed said and when, for resolution, disputes and replay.
ALTER TABLE games
    -- The provider's own lifecycle state (for example suspended versus canceled), which status merges.
    ADD COLUMN provider_state text,
    -- Points per period in order: four regulation periods, then overtimes, as far as reported.
    ADD COLUMN home_periods integer[] NOT NULL DEFAULT '{}',
    ADD COLUMN away_periods integer[] NOT NULL DEFAULT '{}',
    -- When the feed first reported Final (cleared if it later reports otherwise); starts the settlement grace period.
    ADD COLUMN final_at timestamptz,
    ADD CONSTRAINT games_final_at CHECK (final_at IS NULL OR status = 'FINAL');

-- One row per observed change to a game's status, score or clock; never updated or deleted by the app.
CREATE TABLE game_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id text NOT NULL REFERENCES games(id),
    observed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SCHEDULED', 'LIVE', 'FINAL', 'POSTPONED')),
    provider_state text,
    period integer CHECK (period IS NULL OR period >= 1),
    clock text,
    home_score integer CHECK (home_score IS NULL OR home_score >= 0),
    away_score integer CHECK (away_score IS NULL OR away_score >= 0),
    home_periods integer[] NOT NULL,
    away_periods integer[] NOT NULL
);

CREATE INDEX game_snapshots_game_idx ON game_snapshots (game_id, observed_at, id);

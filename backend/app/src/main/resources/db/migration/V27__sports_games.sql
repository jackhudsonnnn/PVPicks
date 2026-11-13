-- Latest snapshot of each game from the sports feed, and per-league feed health.
CREATE TABLE games (
    id text PRIMARY KEY CHECK (id ~ '^(NBA|NFL)-[A-Za-z0-9_-]+$'),
    league text NOT NULL CHECK (league IN ('NBA', 'NFL')),
    provider_id text NOT NULL CHECK (btrim(provider_id) <> ''),
    status text NOT NULL CHECK (status IN ('SCHEDULED', 'LIVE', 'FINAL', 'POSTPONED')),
    starts_at timestamptz NOT NULL,
    period integer CHECK (period IS NULL OR period >= 1),
    clock text,
    home_abbreviation text NOT NULL CHECK (btrim(home_abbreviation) <> ''),
    home_name text NOT NULL,
    home_city text NOT NULL,
    home_score integer CHECK (home_score IS NULL OR home_score >= 0),
    away_abbreviation text NOT NULL CHECK (btrim(away_abbreviation) <> ''),
    away_name text NOT NULL,
    away_city text NOT NULL,
    away_score integer CHECK (away_score IS NULL OR away_score >= 0),
    observed_at timestamptz NOT NULL,
    UNIQUE (league, provider_id)
);

CREATE INDEX games_starts_at_idx ON games (starts_at);
CREATE INDEX games_live_idx ON games (starts_at) WHERE status = 'LIVE';

CREATE TABLE sports_feed_status (
    league text PRIMARY KEY CHECK (league IN ('NBA', 'NFL')),
    last_success_at timestamptz,
    last_failure_at timestamptz,
    last_failure text
);

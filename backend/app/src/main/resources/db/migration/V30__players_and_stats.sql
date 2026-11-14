-- ADR 0005: players and per-game player stats for the player modes.
CREATE TABLE players (
    id text PRIMARY KEY CHECK (id ~ '^(NBA|NFL)-[A-Za-z0-9_-]+$'),
    league text NOT NULL CHECK (league IN ('NBA', 'NFL')),
    provider_id text NOT NULL CHECK (btrim(provider_id) <> ''),
    first_name text NOT NULL,
    last_name text NOT NULL,
    position text,
    team_abbreviation text,
    -- On the provider's current active list (or seen in a game since its last refresh).
    active boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL,
    UNIQUE (league, provider_id)
);

CREATE INDEX players_team_idx ON players (league, team_abbreviation) WHERE active;

-- Each player's current line in a game, in canonical stat keys (see StatLine), whole-game totals.
CREATE TABLE player_game_stats (
    game_id text NOT NULL REFERENCES games(id),
    player_id text NOT NULL REFERENCES players(id),
    team_abbreviation text,
    played boolean NOT NULL,
    stats jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, player_id)
);

-- One row per observed change to a player's line; never updated or deleted by the app.
CREATE TABLE player_stat_snapshots (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_id text NOT NULL REFERENCES games(id),
    player_id text NOT NULL REFERENCES players(id),
    observed_at timestamptz NOT NULL,
    played boolean NOT NULL,
    stats jsonb NOT NULL
);

CREATE INDEX player_stat_snapshots_idx ON player_stat_snapshots (game_id, player_id, observed_at, id);

-- When each league's active-player list was last fully refreshed.
ALTER TABLE sports_feed_status ADD COLUMN players_refreshed_at timestamptz;

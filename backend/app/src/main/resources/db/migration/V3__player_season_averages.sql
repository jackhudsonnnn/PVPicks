-- Each player's per-game averages this season, by stat key (owner decision, 2026-11-24): before kickoff, when every
-- current number is 0, they rank the player picker and suggest a line. Refreshed about daily for players with a game
-- coming up; absent when the plan or the provider has none.
CREATE TABLE player_season_averages (
    player_id text NOT NULL,
    season integer NOT NULL,
    games integer NOT NULL,
    averages jsonb NOT NULL,
    refreshed_at timestamp with time zone NOT NULL,
    CONSTRAINT player_season_averages_pkey PRIMARY KEY (player_id),
    CONSTRAINT player_season_averages_games_check CHECK ((games >= 0)),
    CONSTRAINT player_season_averages_averages_check CHECK ((jsonb_typeof(averages) = 'object'::text)),
    CONSTRAINT player_season_averages_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE
);

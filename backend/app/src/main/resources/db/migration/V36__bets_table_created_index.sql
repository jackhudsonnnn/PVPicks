-- The table list orders tables by their most recent bet (GET /tables, lastBetAt): max(created_at) per table.
CREATE INDEX bets_table_created_idx ON bets (table_id, created_at DESC);

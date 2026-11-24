-- "Run it back" (owner decision, 2026-11-24): a finished card game's rematch points back at it. One rematch per game,
-- so everyone tapping it lands in the same new game.
ALTER TABLE bets ADD COLUMN rematch_of uuid;
ALTER TABLE bets ADD CONSTRAINT bets_rematch_of_fkey FOREIGN KEY (rematch_of) REFERENCES bets(id);
CREATE UNIQUE INDEX bets_one_rematch ON bets USING btree (rematch_of) WHERE (rematch_of IS NOT NULL);

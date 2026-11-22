-- Jackblack (docs: domain rules, "Jackblack"). Everyone who joins takes a turn as dealer, in a random order, and the
-- round's dealer banks it against the rest. The bet row carries the proposer's rules (config), the ante (wager),
-- status and ledger like any other mode; the game itself is the round's state, and each player's worst case is a
-- row so balances and the points limit can sum it.

-- Who may propose Jackblack at a table, set by its hosts.
ALTER TABLE tables ADD COLUMN jackblack_access text NOT NULL DEFAULT 'everyone'
    CHECK (jackblack_access IN ('hosts', 'everyone', 'off'));

ALTER TABLE bets DROP CONSTRAINT bets_mode_check;
ALTER TABLE bets ADD CONSTRAINT bets_mode_check CHECK (mode IN (
    'either_or', 'king_of_the_hill', 'choose_their_fate', 'scorcerer',
    'total_disaster', 'give_and_take', 'prop_hunt', 'u2pick', 'jackblack'
));
ALTER TABLE bets DROP CONSTRAINT bets_game_required;
ALTER TABLE bets ADD CONSTRAINT bets_game_required CHECK (mode IN ('u2pick', 'jackblack') OR game_id IS NOT NULL);

-- One per Jackblack bet, created at the deal. `state` is the whole game (shoe included, so it's never sent as is);
-- `next_deadline` is when the current step or dealer turn times out, for the worker.
CREATE TABLE jackblack_games (
    bet_id uuid PRIMARY KEY REFERENCES bets(id),
    state jsonb NOT NULL CHECK (jsonb_typeof(state) = 'object'),
    next_deadline timestamptz,
    dealt_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX jackblack_games_due_idx ON jackblack_games (next_deadline) WHERE next_deadline IS NOT NULL;

-- A player in the game. `risk_cents` is the most they can lose over the whole ticket (every hand split and doubled,
-- every round lost), held against their points limit while it's in play.
CREATE TABLE jackblack_players (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    risk_cents bigint NOT NULL CHECK (risk_cents >= 0),
    joined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (bet_id, user_id),
    FOREIGN KEY (bet_id, user_id) REFERENCES bet_participants (bet_id, user_id)
);

-- Blackjack (docs: domain rules, "Blackjack"). The proposer deals and banks one hand against everyone who sits
-- down. The bet row carries the dealer's rules (config), status and ledger like any other mode; the hand itself is
-- the round's state, and each seat's bet and current stake are rows so balances and the points limit can sum them.

-- Who may deal at a table, set by its hosts.
ALTER TABLE tables ADD COLUMN blackjack_access text NOT NULL DEFAULT 'everyone'
    CHECK (blackjack_access IN ('hosts', 'everyone', 'off'));

ALTER TABLE bets DROP CONSTRAINT bets_mode_check;
ALTER TABLE bets ADD CONSTRAINT bets_mode_check CHECK (mode IN (
    'either_or', 'king_of_the_hill', 'choose_their_fate', 'scorcerer',
    'total_disaster', 'give_and_take', 'prop_hunt', 'u2pick', 'blackjack'
));
ALTER TABLE bets DROP CONSTRAINT bets_game_required;
ALTER TABLE bets ADD CONSTRAINT bets_game_required CHECK (mode IN ('u2pick', 'blackjack') OR game_id IS NOT NULL);

-- One per Blackjack bet, created at the deal. `state` is the hand (shoe included, so it's never sent as is);
-- `next_deadline` is when the next undecided player times out, for the worker.
CREATE TABLE blackjack_rounds (
    bet_id uuid PRIMARY KEY REFERENCES bets(id),
    state jsonb NOT NULL CHECK (jsonb_typeof(state) = 'object'),
    next_deadline timestamptz,
    dealt_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX blackjack_rounds_due_idx ON blackjack_rounds (next_deadline) WHERE next_deadline IS NOT NULL;

-- A player sitting at the hand. `bet_cents` is what they bought in for; `stake_cents` is everything they have at
-- risk once dealt (doubles, splits and insurance included).
CREATE TABLE blackjack_seats (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    seat_no int NOT NULL CHECK (seat_no BETWEEN 1 AND 7),
    bet_cents bigint NOT NULL CHECK (bet_cents BETWEEN 10 AND 1000),
    stake_cents bigint NOT NULL CHECK (stake_cents >= bet_cents),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bet_id, user_id),
    UNIQUE (bet_id, seat_no),
    FOREIGN KEY (bet_id, user_id) REFERENCES bet_participants (bet_id, user_id)
);

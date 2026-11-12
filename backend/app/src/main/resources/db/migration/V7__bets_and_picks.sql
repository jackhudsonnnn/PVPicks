CREATE TABLE bets (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES tables(id),
    proposer_id uuid NOT NULL REFERENCES users(id),
    mode text NOT NULL CHECK (mode IN (
        'either_or', 'king_of_the_hill', 'choose_their_fate', 'scorcerer',
        'total_disaster', 'give_and_take', 'prop_hunt', 'u2pick'
    )),
    game_id text,
    config jsonb NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    question text NOT NULL CHECK (btrim(question) <> ''),
    choices jsonb NOT NULL CHECK (jsonb_typeof(choices) = 'array' AND jsonb_array_length(choices) >= 2),
    wager_cents bigint NOT NULL CHECK (wager_cents >= 0),
    is_4fun boolean NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING', 'SETTLED', 'WASHED')),
    locks_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    locked_at timestamptz,
    closed_at timestamptz,
    points_settled_at timestamptz,
    baseline jsonb CHECK (baseline IS NULL OR jsonb_typeof(baseline) = 'object'),
    includes_overtime boolean,
    settler_rule text CHECK (settler_rule IN ('anyone', 'non_participants', 'hosts', 'me')),
    called_by uuid REFERENCES users(id),
    winning_choice text,
    wash_reason text,
    remainder_rule text CHECK (remainder_rule IN ('host', 'proposer', 'earliest', 'random')),
    remainder_host_id uuid REFERENCES users(id),
    source_bet_id uuid,
    source_table_id uuid,
    overridden boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT bets_id_table_unique UNIQUE (id, table_id),
    CONSTRAINT bets_source_fk FOREIGN KEY (source_bet_id, source_table_id)
        REFERENCES bets (id, table_id) MATCH FULL,
    CONSTRAINT bets_source_is_other_table CHECK (source_table_id IS NULL OR source_table_id <> table_id),
    CONSTRAINT bets_wager_range CHECK (is_4fun OR wager_cents BETWEEN 10 AND 1000),
    CONSTRAINT bets_game_required CHECK (mode = 'u2pick' OR game_id IS NOT NULL),
    CONSTRAINT bets_locked_remainder CHECK (
        (status = 'ACTIVE' AND locked_at IS NULL AND remainder_rule IS NULL AND remainder_host_id IS NULL)
        OR
        (status <> 'ACTIVE' AND locked_at IS NOT NULL AND remainder_rule IS NOT NULL
            AND ((remainder_rule = 'host' AND remainder_host_id IS NOT NULL)
                OR (remainder_rule <> 'host' AND remainder_host_id IS NULL)))
    ),
    CONSTRAINT bets_unresolved_points CHECK (points_settled_at IS NULL OR status IN ('SETTLED', 'WASHED')),
    CONSTRAINT bets_override_copy CHECK (NOT overridden OR (source_bet_id IS NOT NULL AND status = 'WASHED'))
);

CREATE UNIQUE INDEX bets_one_copy_per_source_and_table
    ON bets (table_id, source_bet_id) WHERE source_bet_id IS NOT NULL;
CREATE INDEX bets_table_status_idx ON bets (table_id, status);
CREATE INDEX bets_due_to_lock_idx ON bets (locks_at, id) WHERE status = 'ACTIVE';

CREATE TABLE bet_participants (
    bet_id uuid NOT NULL REFERENCES bets(id),
    user_id uuid NOT NULL REFERENCES users(id),
    participation_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bet_id, user_id)
);

CREATE TABLE picks (
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL,
    choice text NOT NULL CHECK (
        btrim(choice) <> '' AND lower(btrim(choice)) NOT IN ('no guess', '__no_guess__')
    ),
    picked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bet_id, user_id),
    FOREIGN KEY (bet_id, user_id) REFERENCES bet_participants (bet_id, user_id)
);

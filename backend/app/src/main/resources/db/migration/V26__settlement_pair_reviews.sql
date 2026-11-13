CREATE TABLE settlement_pair_reviews (
    user_id uuid NOT NULL REFERENCES users(id),
    other_user_id uuid NOT NULL REFERENCES users(id),
    revision uuid NOT NULL,
    reviewed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, other_user_id),
    CHECK (user_id <> other_user_id)
);

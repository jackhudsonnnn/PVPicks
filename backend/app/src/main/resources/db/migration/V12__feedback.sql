CREATE TABLE feedback (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    kind text NOT NULL CHECK (kind IN ('mode', 'perk', 'gif', 'bug', 'other')),
    text text NOT NULL CHECK (char_length(btrim(text)) BETWEEN 5 AND 1000),
    link text CHECK (link IS NULL OR link ~* '^https?://[^[:space:]]+$'),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX feedback_user_idx ON feedback (user_id);
CREATE INDEX feedback_created_at_idx ON feedback (created_at DESC, id DESC);

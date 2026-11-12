CREATE TABLE invite_preferences (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    friends text NOT NULL DEFAULT 'open' CHECK (friends IN ('open', 'request', 'closed')),
    following text NOT NULL DEFAULT 'request' CHECK (following IN ('open', 'request', 'closed')),
    updated_at timestamptz NOT NULL DEFAULT now()
);

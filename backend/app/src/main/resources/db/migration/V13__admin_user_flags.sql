CREATE TABLE admin_user_flags (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    flagged boolean NOT NULL DEFAULT false,
    strike_count smallint NOT NULL DEFAULT 0 CHECK (strike_count BETWEEN 0 AND 3)
);

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT auth_sessions_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT auth_sessions_revocation_check CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX auth_sessions_user_id_idx ON auth_sessions (user_id);

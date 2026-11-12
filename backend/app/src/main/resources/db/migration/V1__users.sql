CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    username citext UNIQUE,
    hue smallint,
    password_hash text,
    role text NOT NULL CHECK (role IN ('PLAYER', 'ADMIN')),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT users_identity_check CHECK (
        (status = 'DELETED' AND deleted_at IS NOT NULL
            AND username IS NULL AND hue IS NULL AND password_hash IS NULL)
        OR
        (status IN ('ACTIVE', 'FROZEN') AND deleted_at IS NULL
            AND username IS NOT NULL
            AND username::text ~ '^[a-z0-9_]{3,15}$'
            AND hue IS NOT NULL AND hue BETWEEN 1 AND 5
            AND password_hash IS NOT NULL AND password_hash <> '')
    )
);

CREATE TABLE http_idempotency_key_guard (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    key_verifier bytea NOT NULL CHECK (octet_length(key_verifier) = 32)
);

CREATE TABLE http_idempotency (
    user_id uuid NOT NULL REFERENCES users(id),
    key_digest bytea NOT NULL CHECK (octet_length(key_digest) = 32),
    request_digest bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    operation text NOT NULL CHECK (btrim(operation) <> ''),
    response_status smallint NOT NULL CHECK (response_status = 204),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, key_digest)
);

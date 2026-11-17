-- Single-use codes that let a player set their own password: SETUP for a new account, RESET for a forgotten
-- password (LAN plan, "Accounts and passwords"). Only a SHA-256 of the code is stored. Issuing a new code for a user
-- deletes their unused ones, so at most one is live.
CREATE TABLE account_setup_codes (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    code_hash bytea NOT NULL UNIQUE CHECK (octet_length(code_hash) = 32),
    purpose text NOT NULL CHECK (purpose IN ('SETUP', 'RESET')),
    issued_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    CHECK (expires_at > created_at)
);

CREATE INDEX account_setup_codes_user_idx ON account_setup_codes (user_id);

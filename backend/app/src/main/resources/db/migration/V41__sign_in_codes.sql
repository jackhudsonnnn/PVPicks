-- Players sign in with a short code (typed, or opened from a QR link) instead of a password; only admins have
-- passwords (owner decision, 2026-11-17). An admin can view a player's code at any time and rotate it.
--
-- `lookup` is an HMAC-SHA256 of the normalized code (sign-in finds the player by it); `sealed` is the code encrypted
-- with AES-GCM (12-byte nonce, then ciphertext and tag) so the admin can see it again. Both keys derive from the
-- server's session secret, so a database dump alone reveals no codes. security.SignInCodes owns this table.
CREATE TABLE sign_in_codes (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    lookup bytea NOT NULL UNIQUE CHECK (octet_length(lookup) = 32),
    sealed bytea NOT NULL CHECK (octet_length(sealed) > 12),
    rotated_at timestamptz NOT NULL DEFAULT now(),
    rotated_by uuid REFERENCES users(id)
);

-- Player passwords are gone: a marker no password matches keeps the users constraint (which predates codes)
-- satisfied. Players get their codes from the backend on start (SignInCodes.issueMissing).
UPDATE users SET password_hash = '!code-only' WHERE role = 'PLAYER' AND status IN ('ACTIVE', 'FROZEN');

-- Setup and reset links are replaced by codes.
DROP TABLE account_setup_codes;

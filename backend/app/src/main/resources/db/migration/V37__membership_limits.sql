-- Membership limits (domain rules, "Membership limits"): a table request now expires 30 days after it was sent.
-- Reads already treat older pending requests as expired; this status records it (the hourly job and new requests
-- mark them), so a fresh request to the same table can replace one.
ALTER TABLE table_invites DROP CONSTRAINT table_invites_status_check;
ALTER TABLE table_invites ADD CONSTRAINT table_invites_status_check
    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED'));
UPDATE table_invites SET status = 'EXPIRED'
WHERE status = 'PENDING' AND created_at <= now() - interval '30 days';

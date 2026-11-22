-- Forgive is gone (domain rules, "Settle-ups"): the person owed closes receipts with "Mark as settled", the same
-- final settle as received. Past forgives become received settles and their receipts paid, so History, totals and
-- the admin audit read one kind of closing.
-- Settles are otherwise immutable (V38's settles_protected); this one-time rewrite runs with the guard off, in
-- the migration's transaction.
ALTER TABLE settles DISABLE TRIGGER settles_protected;
UPDATE settles SET kind = 'received' WHERE kind = 'forgiven';
ALTER TABLE settles ENABLE TRIGGER settles_protected;
UPDATE settlement_lines SET status = 'paid', revision = revision + 1, updated_at = clock_timestamp()
WHERE status = 'forgiven';

ALTER TABLE settles DROP CONSTRAINT settles_kind_check;
ALTER TABLE settles ADD CONSTRAINT settles_kind_check CHECK (kind IN ('sent', 'received'));
ALTER TABLE settlement_lines DROP CONSTRAINT settlement_lines_status_check;
ALTER TABLE settlement_lines ADD CONSTRAINT settlement_lines_status_check CHECK (status IN ('unpaid', 'paid'));

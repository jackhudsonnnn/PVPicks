-- Settles (domain rules, "Settle-ups"): one action that closes a chosen set of a pair's receipts at once.
--   sent      the net payer says they paid; final 48 hours later unless the receiver rejects it first
--   received  the net receiver (or either side at a zero net) records it paid; final at once
--   forgiven  the person owed gives up receipts owed to them; final at once
-- A receipt is now unpaid, paid or forgiven, and points at the settle that closed it. Asking to forgive and
-- disputing are gone: a dispute is a rejection, and asking happens in chat.
CREATE TABLE settles (
    id uuid PRIMARY KEY,
    sender_id uuid NOT NULL REFERENCES users(id),
    other_id uuid NOT NULL REFERENCES users(id),
    kind text NOT NULL CHECK (kind IN ('sent', 'received', 'forgiven')),
    -- The selection's net: who pays whom and how much (no payer or payee when it nets to zero).
    payer_id uuid REFERENCES users(id),
    payee_id uuid REFERENCES users(id),
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reject_by timestamptz,
    rejected_at timestamptz,
    CONSTRAINT settles_distinct_parties CHECK (sender_id <> other_id),
    CONSTRAINT settles_net_parties CHECK (
        (amount_cents = 0 AND payer_id IS NULL AND payee_id IS NULL)
        OR (amount_cents > 0 AND payer_id IS NOT NULL AND payee_id IS NOT NULL AND payer_id <> payee_id
            AND (payer_id = sender_id OR payer_id = other_id) AND (payee_id = sender_id OR payee_id = other_id))),
    CONSTRAINT settles_window_only_when_sent CHECK ((kind = 'sent') = (reject_by IS NOT NULL)),
    CONSTRAINT settles_reject_only_sent CHECK (rejected_at IS NULL OR kind = 'sent')
);

-- History and the daily send cap read one pair's settles, newest first.
CREATE INDEX settles_pair_idx
    ON settles (least(sender_id, other_id), greatest(sender_id, other_id), created_at DESC, id DESC);

CREATE TABLE settle_lines (
    settle_id uuid NOT NULL REFERENCES settles(id),
    line_id uuid NOT NULL REFERENCES settlement_lines(id),
    PRIMARY KEY (settle_id, line_id)
);
CREATE INDEX settle_lines_line_idx ON settle_lines (line_id);

ALTER TABLE settlement_lines ADD COLUMN settle_id uuid REFERENCES settles(id);

-- Settles are an audit record: only a rejection is ever written after creation.
CREATE FUNCTION protect_settle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Settles are immutable' USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.id, NEW.sender_id, NEW.other_id, NEW.kind, NEW.payer_id, NEW.payee_id, NEW.amount_cents,
           NEW.created_at, NEW.reject_by)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.sender_id, OLD.other_id, OLD.kind, OLD.payer_id, OLD.payee_id, OLD.amount_cents,
           OLD.created_at, OLD.reject_by)
       OR (OLD.rejected_at IS NOT NULL AND NEW.rejected_at IS DISTINCT FROM OLD.rejected_at) THEN
        RAISE EXCEPTION 'Only a settle''s rejection can change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER settles_protected BEFORE UPDATE OR DELETE ON settles
    FOR EACH ROW EXECUTE FUNCTION protect_settle();
CREATE TRIGGER settle_lines_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON settle_lines
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

-- Existing receipts move to the new model.
-- Closed receipts become one settle each, so History has them: paid as received (by the payee), forgiven as
-- forgiven, at the time they closed.
CREATE TEMP TABLE closed_lines ON COMMIT DROP AS
    SELECT id AS line_id, gen_random_uuid() AS settle_id, payer_id, payee_id, amount_cents, status, updated_at
    FROM settlement_lines WHERE status IN ('paid', 'forgiven');
INSERT INTO settles (id, sender_id, other_id, kind, payer_id, payee_id, amount_cents, created_at)
    SELECT settle_id, payee_id, payer_id, CASE WHEN status = 'paid' THEN 'received' ELSE 'forgiven' END,
           payer_id, payee_id, amount_cents, updated_at
    FROM closed_lines;
INSERT INTO settle_lines (settle_id, line_id) SELECT settle_id, line_id FROM closed_lines;
UPDATE settlement_lines l SET settle_id = c.settle_id FROM closed_lines c WHERE l.id = c.line_id;

-- A receipt marked sent and waiting for confirmation becomes a sent settle whose 48-hour window starts now.
CREATE TEMP TABLE sent_lines ON COMMIT DROP AS
    SELECT id AS line_id, gen_random_uuid() AS settle_id, payer_id, payee_id, amount_cents
    FROM settlement_lines WHERE status = 'sent';
INSERT INTO settles (id, sender_id, other_id, kind, payer_id, payee_id, amount_cents, created_at, reject_by)
    SELECT settle_id, payer_id, payee_id, 'sent', payer_id, payee_id, amount_cents,
           clock_timestamp(), clock_timestamp() + interval '48 hours'
    FROM sent_lines;
INSERT INTO settle_lines (settle_id, line_id) SELECT settle_id, line_id FROM sent_lines;
UPDATE settlement_lines l SET settle_id = s.settle_id, status = 'paid', revision = l.revision + 1,
    updated_at = clock_timestamp()
FROM sent_lines s WHERE l.id = s.line_id;

-- Asked-to-forgive and disputed receipts are simply open again.
UPDATE settlement_lines SET status = 'unpaid', revision = revision + 1, updated_at = clock_timestamp()
WHERE status IN ('forgive_asked', 'disputed');

ALTER TABLE settlement_lines DROP CONSTRAINT settlement_lines_status_check;
ALTER TABLE settlement_lines ADD CONSTRAINT settlement_lines_status_check
    CHECK (status IN ('unpaid', 'paid', 'forgiven'));
ALTER TABLE settlement_lines ADD CONSTRAINT settlement_lines_closed_by_settle
    CHECK ((status = 'unpaid') = (settle_id IS NULL));
ALTER TABLE settlement_lines DROP COLUMN forgive_requested_at;
ALTER TABLE settlement_lines DROP COLUMN forgive_declined_at;

ALTER TABLE bets ADD CONSTRAINT bets_ledger_reference_unique UNIQUE (id, table_id, is_4fun);

CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL,
    bet_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    delta_cents bigint NOT NULL CHECK (delta_cents <> 0),
    -- This constant is part of the foreign key so 4fun bets cannot acquire ledger entries.
    is_4fun boolean NOT NULL DEFAULT false CHECK (NOT is_4fun),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (bet_id, table_id, is_4fun) REFERENCES bets (id, table_id, is_4fun)
);

CREATE INDEX ledger_entries_bet_idx ON ledger_entries (bet_id);
CREATE INDEX ledger_entries_user_table_idx ON ledger_entries (user_id, table_id);

CREATE FUNCTION lock_ledger_bet() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Serialize writers before checking a bet's deferred zero-sum constraint.
    PERFORM 1 FROM bets WHERE id = NEW.bet_id FOR UPDATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_entries_lock_bet
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION lock_ledger_bet();

CREATE FUNCTION check_ledger_zero_sum() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT COALESCE(sum(delta_cents), 0) FROM ledger_entries WHERE bet_id = NEW.bet_id) <> 0 THEN
        RAISE EXCEPTION 'Ledger entries must sum to zero for bet %', NEW.bet_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_zero_sum
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_zero_sum();

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_mutation();

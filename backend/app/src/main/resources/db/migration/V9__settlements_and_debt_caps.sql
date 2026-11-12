CREATE TABLE debt_caps (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    cap_cents integer CHECK (cap_cents >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE settlements (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES tables(id),
    host_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, table_id),
    UNIQUE (id, table_id, host_id)
);

CREATE TABLE settlement_lines (
    id uuid PRIMARY KEY,
    settlement_id uuid NOT NULL,
    table_id uuid NOT NULL,
    host_id uuid NOT NULL,
    payer_id uuid NOT NULL REFERENCES users(id),
    payee_id uuid NOT NULL REFERENCES users(id),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    status text NOT NULL DEFAULT 'unpaid'
        CHECK (status IN ('unpaid', 'sent', 'paid', 'disputed', 'forgive_asked', 'forgiven')),
    revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0),
    forgive_requested_at timestamptz,
    forgive_declined_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (settlement_id, table_id, host_id) REFERENCES settlements (id, table_id, host_id),
    CONSTRAINT settlement_lines_distinct_parties CHECK (payer_id <> payee_id),
    CONSTRAINT settlement_lines_include_host CHECK (payer_id = host_id OR payee_id = host_id)
);

CREATE UNIQUE INDEX settlement_lines_one_per_counterparty
    ON settlement_lines (settlement_id, (CASE WHEN payer_id = host_id THEN payee_id ELSE payer_id END));
CREATE INDEX settlement_lines_payer_idx ON settlement_lines (payer_id, status);
CREATE INDEX settlement_lines_payee_idx ON settlement_lines (payee_id, status);

CREATE TABLE settlement_bets (
    bet_id uuid PRIMARY KEY,
    table_id uuid NOT NULL,
    settlement_id uuid NOT NULL,
    FOREIGN KEY (bet_id, table_id) REFERENCES bets (id, table_id),
    FOREIGN KEY (settlement_id, table_id) REFERENCES settlements (id, table_id)
);

CREATE INDEX settlement_bets_settlement_idx ON settlement_bets (settlement_id);

CREATE FUNCTION reject_settlement_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Settlement history is immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER settlements_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON settlements
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();
CREATE TRIGGER settlement_bets_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON settlement_bets
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();
CREATE TRIGGER settlement_lines_no_removal
    BEFORE DELETE OR TRUNCATE ON settlement_lines
    FOR EACH STATEMENT EXECUTE FUNCTION reject_settlement_history_mutation();

CREATE FUNCTION protect_settlement_line_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.settlement_id, NEW.table_id, NEW.host_id, NEW.payer_id,
           NEW.payee_id, NEW.amount_cents, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.settlement_id, OLD.table_id, OLD.host_id, OLD.payer_id,
           OLD.payee_id, OLD.amount_cents, OLD.created_at) THEN
        RAISE EXCEPTION 'Settlement line identity and amount are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_lines_identity_immutable
    BEFORE UPDATE ON settlement_lines
    FOR EACH ROW EXECUTE FUNCTION protect_settlement_line_identity();

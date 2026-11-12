CREATE TABLE outbox (
    id uuid PRIMARY KEY,
    aggregate_type text NOT NULL CHECK (btrim(aggregate_type) <> ''),
    aggregate_id text NOT NULL CHECK (btrim(aggregate_id) <> ''),
    event_type text NOT NULL CHECK (btrim(event_type) <> ''),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at, id) WHERE published_at IS NULL;

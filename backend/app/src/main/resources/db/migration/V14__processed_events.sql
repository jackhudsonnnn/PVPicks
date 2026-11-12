CREATE TABLE processed_events (
    consumer_name text NOT NULL CHECK (btrim(consumer_name) <> ''),
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

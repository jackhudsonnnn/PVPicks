CREATE TABLE quickpick_subscriptions (
    subscriber_table_id uuid NOT NULL REFERENCES tables(id),
    source_table_id uuid NOT NULL REFERENCES tables(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber_table_id, source_table_id),
    CONSTRAINT quickpick_subscriptions_not_self CHECK (subscriber_table_id <> source_table_id)
);

CREATE INDEX quickpick_subscriptions_source_idx ON quickpick_subscriptions (source_table_id);

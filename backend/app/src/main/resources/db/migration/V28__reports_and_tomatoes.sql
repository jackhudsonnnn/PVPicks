-- Reports from players for the (future) moderation queue.
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    reporter_id uuid NOT NULL REFERENCES users(id),
    target_id uuid NOT NULL REFERENCES users(id),
    reason text NOT NULL CHECK (reason IN ('harassment', 'settlement', 'spam', 'offensive', 'other')),
    note text NOT NULL CHECK (char_length(note) <= 500 AND (reason <> 'other' OR char_length(btrim(note)) > 0)),
    table_id uuid REFERENCES tables(id),
    -- The reported message, and its content as it was when reported, so moderators see what was reported even if
    -- it later changes.
    message_id uuid REFERENCES chat_messages(id) ON DELETE SET NULL,
    message_snapshot text,
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reports_not_self CHECK (reporter_id <> target_id),
    CONSTRAINT reports_message_needs_table CHECK (message_id IS NULL OR table_id IS NOT NULL)
);

CREATE INDEX reports_reporter_created_idx ON reports (reporter_id, created_at DESC);
CREATE INDEX reports_open_created_idx ON reports (created_at DESC) WHERE status = 'OPEN';

-- Tomatoes thrown at a table member: a playful, short-lived effect every member sees. Rows only need to outlive the
-- realtime round trip, so throwing prunes the table's rows older than a day.
CREATE TABLE tomato_throws (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES tables(id),
    thrower_id uuid NOT NULL REFERENCES users(id),
    target_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tomato_throws_not_self CHECK (thrower_id <> target_id)
);

CREATE INDEX tomato_throws_table_created_idx ON tomato_throws (table_id, created_at DESC);
CREATE INDEX tomato_throws_thrower_idx ON tomato_throws (thrower_id, table_id, created_at DESC);

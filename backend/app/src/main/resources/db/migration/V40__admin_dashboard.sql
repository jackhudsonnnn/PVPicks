-- Admin dashboard: the admin audit log, request performance buckets and the feedback inbox.

-- Every user audit an admin opens and every search they run inside one. Kept with the admin's id even after
-- the subject deletes their account (the subject's row stays as a tombstone).
CREATE TABLE admin_audit_log (
    id uuid PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES users(id),
    action text NOT NULL CHECK (action IN ('view_user', 'search_activity')),
    subject_id uuid NOT NULL REFERENCES users(id),
    query text CHECK (query IS NULL OR char_length(query) <= 200),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_log_created_idx ON admin_audit_log (created_at DESC, id DESC);
CREATE INDEX admin_audit_log_subject_idx ON admin_audit_log (subject_id, created_at DESC);

-- One row per minute, source, method and route: request count, 5xx, other 4xx, 429s and a latency histogram.
-- `buckets[i]` counts requests at or under the i-th bound of admin.PerfBuckets (ms), the last one counting
-- everything slower. Server rows time the backend; client rows are what browsers measured (API calls including
-- the network, and page loads). Rows older than seven days are deleted.
CREATE TABLE perf_minutes (
    minute timestamptz NOT NULL CHECK (date_trunc('minute', minute) = minute),
    source text NOT NULL CHECK (source IN ('server', 'client')),
    method text NOT NULL CHECK (char_length(method) BETWEEN 1 AND 10),
    route text NOT NULL CHECK (char_length(route) BETWEEN 1 AND 200),
    requests integer NOT NULL CHECK (requests > 0),
    server_errors integer NOT NULL CHECK (server_errors BETWEEN 0 AND requests),
    client_errors integer NOT NULL CHECK (client_errors BETWEEN 0 AND requests),
    rate_limited integer NOT NULL CHECK (rate_limited BETWEEN 0 AND requests),
    total_ms bigint NOT NULL CHECK (total_ms >= 0),
    max_ms integer NOT NULL CHECK (max_ms >= 0),
    buckets integer[] NOT NULL,
    PRIMARY KEY (minute, source, method, route)
);

-- The feedback inbox: New until an admin marks it Done, with a private note.
ALTER TABLE feedback ADD COLUMN status text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'DONE'));
ALTER TABLE feedback ADD COLUMN admin_note text CHECK (admin_note IS NULL OR char_length(admin_note) <= 500);
ALTER TABLE feedback ADD COLUMN handled_by uuid REFERENCES users(id);
ALTER TABLE feedback ADD COLUMN handled_at timestamptz;

-- A user's audit lists their messages and proposed bets newest first.
DROP INDEX chat_messages_author_idx;
CREATE INDEX chat_messages_author_idx ON chat_messages (author_id, created_at DESC, id DESC)
    WHERE author_id IS NOT NULL;
CREATE INDEX bets_proposer_idx ON bets (proposer_id, created_at DESC, id DESC);
CREATE INDEX picks_user_idx ON picks (user_id);

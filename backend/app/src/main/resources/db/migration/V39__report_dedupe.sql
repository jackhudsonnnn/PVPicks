-- One open report per reporter per target: reporting someone again while your report is open updates it instead of
-- adding a row. Older open duplicates are folded into the newest by dismissing them.
UPDATE reports r SET status = 'DISMISSED'
WHERE r.status = 'OPEN' AND EXISTS (
    SELECT 1 FROM reports newer
    WHERE newer.status = 'OPEN' AND newer.reporter_id = r.reporter_id AND newer.target_id = r.target_id
      AND (newer.created_at, newer.id) > (r.created_at, r.id)
);

CREATE UNIQUE INDEX reports_one_open_per_pair ON reports (reporter_id, target_id) WHERE status = 'OPEN';

-- Keyset paging for the admin queue (all reports, newest first).
CREATE INDEX reports_created_idx ON reports (created_at DESC, id DESC);

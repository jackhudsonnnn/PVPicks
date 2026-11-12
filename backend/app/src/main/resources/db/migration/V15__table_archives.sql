ALTER TABLE tables ADD COLUMN archived_at timestamptz;

CREATE TABLE table_archive_members (
    table_id uuid NOT NULL REFERENCES tables(id),
    user_id uuid NOT NULL REFERENCES users(id),
    PRIMARY KEY (table_id, user_id)
);

CREATE INDEX table_archive_members_user_idx ON table_archive_members (user_id);

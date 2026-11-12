CREATE TABLE follows (
    follower_id uuid NOT NULL REFERENCES users(id),
    followee_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT follows_not_self CHECK (follower_id <> followee_id)
);

CREATE INDEX follows_followee_id_idx ON follows (followee_id);

CREATE TABLE blocks (
    blocker_id uuid NOT NULL REFERENCES users(id),
    blocked_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_id_idx ON blocks (blocked_id);

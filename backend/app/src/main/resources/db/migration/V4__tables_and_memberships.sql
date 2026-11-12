CREATE TABLE tables (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (btrim(name) <> ''),
    emoji text NOT NULL CHECK (btrim(emoji) <> ''),
    remainder_rule text NOT NULL DEFAULT 'host'
        CHECK (remainder_rule IN ('host', 'proposer', 'earliest', 'random')),
    hosts_only_bets boolean NOT NULL DEFAULT false,
    u2pick_enabled boolean NOT NULL DEFAULT true,
    quickpick_code text NOT NULL UNIQUE
        CHECK (quickpick_code ~ '^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$'),
    quickpicks_enabled boolean NOT NULL DEFAULT false,
    quickpick_sharing boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sharing_requires_quickpicks CHECK (NOT quickpick_sharing OR quickpicks_enabled)
);

CREATE TABLE table_members (
    table_id uuid NOT NULL REFERENCES tables(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role text NOT NULL CHECK (role IN ('HOST', 'MEMBER')),
    can_bet boolean NOT NULL DEFAULT true,
    can_text boolean NOT NULL DEFAULT true,
    can_react boolean NOT NULL DEFAULT true,
    can_send_gifs boolean NOT NULL DEFAULT true,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (table_id, user_id),
    CONSTRAINT hosts_have_all_permissions CHECK (
        role <> 'HOST' OR (can_bet AND can_text AND can_react AND can_send_gifs)
    )
);

CREATE INDEX table_members_user_id_idx ON table_members (user_id);

CREATE TABLE table_invites (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES tables(id),
    from_id uuid NOT NULL REFERENCES users(id),
    user_id uuid NOT NULL REFERENCES users(id),
    status text NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT table_invites_not_self CHECK (from_id <> user_id)
);

CREATE UNIQUE INDEX table_invites_one_pending_per_user
    ON table_invites (table_id, user_id) WHERE status = 'PENDING';
CREATE INDEX table_invites_recipient_status_idx ON table_invites (user_id, status);

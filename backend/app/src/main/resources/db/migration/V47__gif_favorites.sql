-- Starred GIFs (docs: domain rules, "Chat"): each player's own shortlist from the GIF library, shown first in the
-- picker. Personal data: deleted with the account's chat data.
CREATE TABLE gif_favorites (
    user_id uuid NOT NULL REFERENCES users(id),
    gif_id text NOT NULL CHECK (gif_id ~ '^[a-z0-9_-]{1,40}$'),
    starred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, gif_id)
);

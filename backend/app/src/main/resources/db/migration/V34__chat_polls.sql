-- Chat polls: a member asks a question with 2–6 options; every member can vote for one option and change it
-- any time. Polls never close. Hosts can turn polls off for the table and take the permission from a member.

ALTER TABLE tables ADD COLUMN polls_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE table_members ADD COLUMN can_poll boolean NOT NULL DEFAULT true;
ALTER TABLE table_members DROP CONSTRAINT hosts_have_all_permissions;
ALTER TABLE table_members ADD CONSTRAINT hosts_have_all_permissions CHECK (
    role <> 'HOST' OR (can_bet AND can_text AND can_react AND can_send_gifs AND can_poll)
);

ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_kind_check;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_kind_check
    CHECK (kind IN ('text', 'gif', 'bet', 'system', 'settlement', 'poll'));
ALTER TABLE chat_messages ADD COLUMN poll_options text[];
ALTER TABLE chat_messages DROP CONSTRAINT chat_message_shape;
-- A poll keeps its question in text and its options in poll_options.
ALTER TABLE chat_messages ADD CONSTRAINT chat_message_shape CHECK (
    (kind = 'text' AND author_id IS NOT NULL AND text IS NOT NULL AND btrim(text) <> ''
        AND gif_id IS NULL AND bet_id IS NULL AND settlement_id IS NULL AND poll_options IS NULL)
    OR (kind = 'gif' AND author_id IS NOT NULL AND gif_id IS NOT NULL AND btrim(gif_id) <> ''
        AND text IS NULL AND bet_id IS NULL AND settlement_id IS NULL AND poll_options IS NULL)
    OR (kind = 'bet' AND author_id IS NOT NULL AND bet_id IS NOT NULL
        AND text IS NULL AND gif_id IS NULL AND settlement_id IS NULL AND poll_options IS NULL)
    OR (kind = 'system' AND author_id IS NULL AND text IS NOT NULL AND btrim(text) <> ''
        AND gif_id IS NULL AND bet_id IS NULL AND settlement_id IS NULL AND poll_options IS NULL)
    OR (kind = 'settlement' AND author_id IS NULL AND settlement_id IS NOT NULL
        AND text IS NULL AND gif_id IS NULL AND bet_id IS NULL AND poll_options IS NULL)
    OR (kind = 'poll' AND author_id IS NOT NULL AND text IS NOT NULL AND btrim(text) <> ''
        AND poll_options IS NOT NULL AND cardinality(poll_options) BETWEEN 2 AND 6
        AND array_position(poll_options, NULL) IS NULL
        AND gif_id IS NULL AND bet_id IS NULL AND settlement_id IS NULL)
);

CREATE TABLE poll_votes (
    message_id uuid NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id),
    option_index smallint NOT NULL CHECK (option_index BETWEEN 0 AND 5),
    voted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX poll_votes_user_idx ON poll_votes (user_id);

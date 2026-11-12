CREATE SEQUENCE chat_message_order_seq AS bigint;

CREATE TABLE chat_messages (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES tables(id),
    message_order bigint NOT NULL UNIQUE,
    kind text NOT NULL CHECK (kind IN ('text', 'gif', 'bet', 'system', 'settlement')),
    author_id uuid REFERENCES users(id),
    text text,
    gif_id text,
    bet_id uuid,
    settlement_id uuid,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (bet_id, table_id) REFERENCES bets (id, table_id),
    FOREIGN KEY (settlement_id, table_id) REFERENCES settlements (id, table_id),
    CONSTRAINT chat_message_shape CHECK (
        (kind = 'text' AND author_id IS NOT NULL AND text IS NOT NULL AND btrim(text) <> ''
            AND gif_id IS NULL AND bet_id IS NULL AND settlement_id IS NULL)
        OR (kind = 'gif' AND author_id IS NOT NULL AND gif_id IS NOT NULL AND btrim(gif_id) <> ''
            AND text IS NULL AND bet_id IS NULL AND settlement_id IS NULL)
        OR (kind = 'bet' AND author_id IS NOT NULL AND bet_id IS NOT NULL
            AND text IS NULL AND gif_id IS NULL AND settlement_id IS NULL)
        OR (kind = 'system' AND author_id IS NULL AND text IS NOT NULL AND btrim(text) <> ''
            AND gif_id IS NULL AND bet_id IS NULL AND settlement_id IS NULL)
        OR (kind = 'settlement' AND author_id IS NULL AND settlement_id IS NOT NULL
            AND text IS NULL AND gif_id IS NULL AND bet_id IS NULL)
    )
);

ALTER SEQUENCE chat_message_order_seq OWNED BY chat_messages.message_order;
CREATE INDEX chat_messages_table_order_idx ON chat_messages (table_id, message_order);
CREATE INDEX chat_messages_author_idx ON chat_messages (author_id) WHERE author_id IS NOT NULL;
CREATE UNIQUE INDEX chat_messages_one_per_settlement
    ON chat_messages (settlement_id) WHERE settlement_id IS NOT NULL;

CREATE FUNCTION assign_chat_message_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Assign the sequence after locking the table, so concurrent commits cannot reorder its messages.
    PERFORM 1 FROM tables WHERE id = NEW.table_id FOR UPDATE;
    NEW.message_order := nextval('chat_message_order_seq');
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER chat_messages_assign_position
    BEFORE INSERT ON chat_messages
    FOR EACH ROW EXECUTE FUNCTION assign_chat_message_position();

CREATE FUNCTION protect_chat_message_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.table_id, NEW.message_order, NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.table_id, OLD.message_order, OLD.created_at) THEN
        RAISE EXCEPTION 'Chat message position is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER chat_messages_position_immutable
    BEFORE UPDATE ON chat_messages
    FOR EACH ROW EXECUTE FUNCTION protect_chat_message_position();

CREATE TABLE reactions (
    message_id uuid NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id),
    emoji text NOT NULL CHECK (btrim(emoji) <> ''),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX reactions_user_idx ON reactions (user_id);

CREATE TABLE chat_read_positions (
    table_id uuid NOT NULL REFERENCES tables(id),
    user_id uuid NOT NULL REFERENCES users(id),
    last_read_order bigint NOT NULL CHECK (last_read_order > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (table_id, user_id)
);

CREATE INDEX chat_read_positions_user_idx ON chat_read_positions (user_id);

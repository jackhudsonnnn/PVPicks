CREATE TABLE notification_preferences (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    enabled boolean NOT NULL DEFAULT true,
    errors boolean NOT NULL DEFAULT true,
    ticket_status boolean NOT NULL DEFAULT true,
    ticket_scope text NOT NULL DEFAULT 'mine' CHECK (ticket_scope IN ('mine', 'all')),
    chats boolean NOT NULL DEFAULT true,
    invites boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notification_muted_tables (
    user_id uuid NOT NULL REFERENCES users(id),
    table_id uuid NOT NULL REFERENCES tables(id),
    PRIMARY KEY (user_id, table_id)
);

ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_notification_source_unique
    UNIQUE (id, table_id, author_id);
CREATE SEQUENCE notification_order_seq AS bigint;

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL REFERENCES users(id),
    source_event_id uuid NOT NULL,
    notification_key text NOT NULL CHECK (btrim(notification_key) <> ''),
    notification_order bigint NOT NULL UNIQUE,
    tone text NOT NULL CHECK (tone IN (
        'error', 'chat', 'new', 'locked', 'won', 'lost', 'resolved', 'washed', 'invite', 'settle'
    )),
    title text NOT NULL CHECK (btrim(title) <> ''),
    description text,
    table_id uuid REFERENCES tables(id),
    bet_id uuid,
    actor_id uuid REFERENCES users(id),
    source_message_id uuid,
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    UNIQUE (recipient_id, source_event_id, notification_key),
    FOREIGN KEY (bet_id, table_id) REFERENCES bets (id, table_id),
    FOREIGN KEY (source_message_id, table_id, actor_id)
        REFERENCES chat_messages (id, table_id, author_id) ON DELETE CASCADE,
    CONSTRAINT notifications_bet_has_table CHECK (bet_id IS NULL OR table_id IS NOT NULL),
    CONSTRAINT notifications_chat_source CHECK (
        (tone = 'chat' AND source_message_id IS NOT NULL AND table_id IS NOT NULL AND actor_id IS NOT NULL)
        OR (tone <> 'chat' AND source_message_id IS NULL)
    )
);

ALTER SEQUENCE notification_order_seq OWNED BY notifications.notification_order;
CREATE INDEX notifications_recipient_order_idx ON notifications (recipient_id, notification_order DESC);
CREATE INDEX notifications_unread_idx ON notifications (recipient_id, notification_order DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_actor_idx ON notifications (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX notifications_source_message_idx ON notifications (source_message_id) WHERE source_message_id IS NOT NULL;

CREATE FUNCTION assign_notification_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Lock before allocating an order so each recipient's committed inbox remains stable.
    PERFORM 1 FROM users WHERE id = NEW.recipient_id FOR UPDATE;
    NEW.notification_order := nextval('notification_order_seq');
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER notifications_assign_position
    BEFORE INSERT ON notifications
    FOR EACH ROW EXECUTE FUNCTION assign_notification_position();

CREATE FUNCTION protect_notification_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.recipient_id, NEW.source_event_id, NEW.notification_key,
           NEW.notification_order, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.recipient_id, OLD.source_event_id, OLD.notification_key,
           OLD.notification_order, OLD.created_at) THEN
        RAISE EXCEPTION 'Notification identity and position are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notifications_position_immutable
    BEFORE UPDATE ON notifications
    FOR EACH ROW EXECUTE FUNCTION protect_notification_position();

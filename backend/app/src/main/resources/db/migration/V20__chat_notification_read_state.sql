CREATE OR REPLACE FUNCTION assign_notification_position() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM users WHERE id = NEW.recipient_id FOR UPDATE;
    NEW.notification_order := nextval('notification_order_seq');
    NEW.created_at := clock_timestamp();
    IF NEW.tone = 'chat' AND NEW.read_at IS NULL AND EXISTS (
        SELECT 1 FROM chat_read_positions pos
        JOIN chat_messages m ON m.id = NEW.source_message_id AND m.table_id = pos.table_id
        WHERE pos.table_id = NEW.table_id AND pos.user_id = NEW.recipient_id
          AND m.message_order <= pos.last_read_order
    ) THEN
        NEW.read_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

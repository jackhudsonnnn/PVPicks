DROP TABLE notifications;
DROP TABLE notification_muted_tables;
DROP TABLE notification_preferences;
DROP FUNCTION assign_notification_position();
DROP FUNCTION protect_notification_position();
ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_notification_source_unique;

ALTER TABLE chat_messages
    ADD COLUMN system_event text,
    ADD COLUMN system_subject_id uuid REFERENCES users(id),
    ADD CONSTRAINT chat_system_attribution_shape CHECK (
        (system_event IS NULL AND system_subject_id IS NULL)
        OR (
            kind = 'system' AND system_event IS NOT NULL AND system_subject_id IS NOT NULL
            AND ((system_event = 'host_promoted' AND text = 'A member is now a host')
                 OR (system_event = 'host_demoted' AND text = 'A member is no longer a host'))
        )
    );

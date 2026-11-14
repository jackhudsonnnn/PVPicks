-- Host overrides of sports bets (domain rules, "Sports outcomes and host overrides"): who last overrode the result
-- and when. Corrections append compensating ledger entries; earlier entries are never edited.
ALTER TABLE bets
    ADD COLUMN overridden_by uuid REFERENCES users(id),
    ADD COLUMN overridden_at timestamptz,
    ADD CONSTRAINT bets_override_audit CHECK ((overridden_by IS NULL) = (overridden_at IS NULL));

-- The chat line for an override names the host by their current username ("<host> set ...").
ALTER TABLE chat_messages DROP CONSTRAINT chat_system_attribution_shape;
ALTER TABLE chat_messages ADD CONSTRAINT chat_system_attribution_shape CHECK (
    (system_event IS NULL AND system_subject_id IS NULL)
    OR (
        kind = 'system' AND system_event IS NOT NULL AND system_subject_id IS NOT NULL
        AND ((system_event = 'host_promoted' AND text = 'A member is now a host')
             OR (system_event = 'host_demoted' AND text = 'A member is no longer a host')
             OR (system_event = 'bet_override' AND text LIKE 'A host %'))
    )
);

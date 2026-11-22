package com.p2picks.app.chat;

/** SQL fragments shared by every chat read, so windows and previews agree on what a viewer sees. */
final class ChatVisibility {
    /** Whether message {@code m} (author {@code author}) is visible to {@code viewer}. */
    static final String VISIBLE = """
            (m.kind IN ('bet', 'system', 'settlement')
             OR (m.kind IN ('text', 'gif', 'poll') AND author.status <> 'DELETED'
                 AND NOT EXISTS (SELECT 1 FROM blocks b
                                 WHERE b.blocker_id = viewer.id AND b.blocked_id = m.author_id)))
            """;

    /** Message {@code m}'s display text; host events name their subject ({@code subject}) at read time. */
    static final String TEXT = """
            CASE m.system_event
                WHEN 'host_promoted' THEN COALESCE(subject.username::text, 'Deleted user') || ' is now a host'
                WHEN 'host_demoted' THEN COALESCE(subject.username::text, 'Deleted user') || ' is no longer a host'
                WHEN 'bet_override' THEN COALESCE(subject.username::text, 'Deleted user') || substr(m.text, 7)
                ELSE m.text
            END
            """;

    private ChatVisibility() {}
}

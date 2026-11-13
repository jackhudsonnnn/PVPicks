package com.p2picks.app.chat;

final class ChatVisibility {
    static final String VISIBLE = """
            (m.kind IN ('bet', 'system', 'settlement')
             OR (m.kind IN ('text', 'gif') AND author.status <> 'DELETED'
                 AND NOT EXISTS (SELECT 1 FROM blocks b
                                 WHERE b.blocker_id = viewer.id AND b.blocked_id = m.author_id)))
            """;

    private ChatVisibility() {}
}

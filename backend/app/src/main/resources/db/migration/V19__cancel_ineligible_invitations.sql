UPDATE table_invites i SET status = 'CANCELLED'
WHERE i.status = 'PENDING' AND (
    NOT EXISTS (SELECT 1 FROM table_members m
        WHERE m.table_id = i.table_id AND m.user_id = i.from_id AND m.role = 'HOST')
    OR NOT EXISTS (SELECT 1 FROM follows f
        WHERE f.follower_id = i.user_id AND f.followee_id = i.from_id)
    OR (CASE WHEN EXISTS (SELECT 1 FROM follows f
            WHERE f.follower_id = i.from_id AND f.followee_id = i.user_id)
        THEN COALESCE((SELECT p.friends FROM invite_preferences p WHERE p.user_id = i.user_id), 'open')
        ELSE COALESCE((SELECT p.following FROM invite_preferences p WHERE p.user_id = i.user_id), 'request')
        END) = 'closed'
);

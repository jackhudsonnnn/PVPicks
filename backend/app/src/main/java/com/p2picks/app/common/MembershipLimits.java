package com.p2picks.app.common;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Caps that keep every list a person or table can grow bounded, so no one can flood someone else's tables, requests
 * or a table's member list (docs/site/reference/domain-rules.md, "Membership limits"). Callers count under the
 * locks they already hold: the user row for a person's counts, the table row for a table's.
 */
public final class MembershipLimits {
    /** Open tables one account can be in, hosting or not. Deleted (archived) tables don't count. */
    public static final int TABLES_PER_USER = 100;
    /** Members one table can have, hosts included. */
    public static final int MEMBERS_PER_TABLE = 500;
    /** Table requests one account can have waiting. */
    public static final int WAITING_REQUESTS = 100;
    /** A waiting request expires this many days after it was sent. */
    public static final int REQUEST_DAYS = 30;

    /** SQL: invitation row {@code i} is still waiting (pending and inside its lifetime). */
    public static final String WAITING = "i.status = 'PENDING' AND i.created_at > now() - interval '30 days'";
    /** SQL: invitation row {@code i} is pending but past its lifetime, so it counts as expired. */
    public static final String LAPSED = "i.status = 'PENDING' AND i.created_at <= now() - interval '30 days'";

    public static final String TABLE_LIMIT_MESSAGE =
            "You're at " + TABLES_PER_USER + " tables. Leave one first.";
    public static final String TABLE_FULL_MESSAGE =
            "This table is full (" + MEMBERS_PER_TABLE + " members).";

    private MembershipLimits() {}

    public static int tables(JdbcTemplate jdbc, UUID user) {
        return count(jdbc, """
                SELECT count(*) FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.user_id = ? AND t.archived_at IS NULL
                """, user);
    }

    public static int members(JdbcTemplate jdbc, UUID table) {
        return count(jdbc, "SELECT count(*) FROM table_members WHERE table_id = ?", table);
    }

    public static int waitingRequests(JdbcTemplate jdbc, UUID user) {
        return count(jdbc, "SELECT count(*) FROM table_invites i WHERE i.user_id = ? AND " + WAITING, user);
    }

    /** Marks one table's lapsed request to a user expired, so a new one can take its place. */
    public static void expireLapsed(JdbcTemplate jdbc, UUID table, UUID user) {
        jdbc.update("UPDATE table_invites i SET status = 'EXPIRED' WHERE i.table_id = ? AND i.user_id = ? AND "
                + LAPSED, table, user);
    }

    private static int count(JdbcTemplate jdbc, String sql, UUID id) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, id));
    }
}

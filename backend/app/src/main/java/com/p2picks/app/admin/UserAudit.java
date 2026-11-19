package com.p2picks.app.admin;

import com.p2picks.app.common.PageCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything an admin sees about one player: account, moderation, people, tables, betting record, settle-ups,
 * recent activity, and who audited them before. Read-only. Bets still Active are left out of every pick and
 * participation count until they lock, the same rule players live under (domain rules, "Admin audit views").
 * The bets they proposed are public as soon as they're proposed, so those count.
 */
@Component
class UserAudit {
    static final int ACTIVITY_PAGE = 30;
    static final int ACTIVITY_DAYS = 30;

    record Profile(UUID id, String username, Integer hue, String status, Instant createdAt, Instant deletedAt,
            Instant lastSignInAt, int activeSessions, Instant codeRotatedAt) {}

    record Counts(int open, int resolved, int dismissed) {}

    record Report(UUID id, String reason, String note, String status, Instant createdAt, UUID otherId,
            String otherName, Integer otherHue, String tableName, String messageSnapshot) {}

    record Moderation(int strikes, boolean frozen, Counts against, Counts filed, List<Report> recentAgainst,
            List<Report> recentFiled) {}

    record People(int following, int followers, int friends, int blocking, int blockedBy) {}

    record TableRow(UUID id, String name, String emoji, String role, Instant joinedAt, boolean archived,
            int members, int messages, int polls, int betsProposed, int picks, long netCents) {}

    record Record(int won, int lost) {}

    record ModeRow(String mode, int won, int lost, int washed, int open) {}

    record Betting(int proposed, int picks, int pending, Record decided, int washed, Record fourFun,
            Double winRate, long netCents, Long biggestWinCents, Long biggestLossCents, List<ModeRow> byMode) {}

    record Money(int settles, long paidCents, long collectedCents, int sendsRejectedByOthers, int rejectedTheirSends, int pendingRejectWindow,
            Integer debtCapCents) {}

    record Day(LocalDate date, int messages, int picks, int bets, int settles) {}

    record Audit(Profile profile, Moderation moderation, People people, List<TableRow> tables, int archivedTables,
            Betting betting, Money money, List<Day> activity) {}

    record Item(String kind, UUID id, UUID tableId, String tableName, String text, List<String> options,
            String mode, String status, Long wagerCents, Instant createdAt) {}

    record Activity(List<Item> items, String nextCursor) {}

    private final JdbcTemplate jdbc;

    UserAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean exists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND role = 'PLAYER')", Boolean.class, userId));
    }

    /** The audit, or empty when there's no such player. */
    @Transactional(readOnly = true)
    Optional<Audit> audit(UUID userId) {
        Optional<Profile> profile = profile(userId);
        if (profile.isEmpty()) return Optional.empty();
        List<TableRow> tables = tables(userId);
        int archived = jdbc.queryForObject(
                "SELECT count(*) FROM table_archive_members WHERE user_id = ?", Integer.class, userId);
        return Optional.of(new Audit(profile.get(), moderation(userId), people(userId), tables, archived,
                betting(userId), money(userId), activity(userId)));
    }

    private Optional<Profile> profile(UUID userId) {
        return jdbc.query("""
                SELECT u.id, u.username::text AS username, u.hue, u.status, u.created_at, u.deleted_at,
                       (SELECT rotated_at FROM sign_in_codes WHERE user_id = u.id) AS code_rotated_at,
                       (SELECT max(created_at) FROM auth_sessions WHERE user_id = u.id) AS last_sign_in,
                       (SELECT count(*) FROM auth_sessions WHERE user_id = u.id AND revoked_at IS NULL
                          AND expires_at > now()) AS active_sessions
                FROM users u WHERE u.id = ? AND u.role = 'PLAYER'
                """, (rs, row) -> new Profile(rs.getObject("id", UUID.class), rs.getString("username"),
                        (Integer) rs.getObject("hue", Integer.class), rs.getString("status"),
                        instant(rs, "created_at"), instant(rs, "deleted_at"), instant(rs, "last_sign_in"),
                        rs.getInt("active_sessions"), instant(rs, "code_rotated_at")), userId)
                .stream().findFirst();
    }

    private Moderation moderation(UUID userId) {
        var flags = jdbc.query("""
                SELECT f.strike_count, u.status = 'FROZEN' AS frozen
                FROM users u LEFT JOIN admin_user_flags f ON f.user_id = u.id WHERE u.id = ?
                """, (rs, row) -> new int[] {rs.getInt("strike_count"), rs.getBoolean("frozen") ? 1 : 0}, userId)
                .getFirst();
        return new Moderation(flags[0], flags[1] == 1, reportCounts("target_id", userId),
                reportCounts("reporter_id", userId), reports("target_id", "reporter_id", userId),
                reports("reporter_id", "target_id", userId));
    }

    private Counts reportCounts(String column, UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'OPEN') AS open,
                       count(*) FILTER (WHERE status = 'RESOLVED') AS resolved,
                       count(*) FILTER (WHERE status = 'DISMISSED') AS dismissed
                FROM reports WHERE %s = ?
                """.formatted(column), (rs, row) -> new Counts(rs.getInt("open"), rs.getInt("resolved"),
                        rs.getInt("dismissed")), userId);
    }

    private List<Report> reports(String self, String other, UUID userId) {
        return jdbc.query("""
                SELECT r.id, r.reason, r.note, r.status, r.created_at, r.%2$s AS other_id,
                       o.username::text AS other_name, o.hue AS other_hue, t.name AS table_name, r.message_snapshot
                FROM reports r JOIN users o ON o.id = r.%2$s LEFT JOIN tables t ON t.id = r.table_id
                WHERE r.%1$s = ? ORDER BY r.created_at DESC, r.id DESC LIMIT 10
                """.formatted(self, other), (rs, row) -> new Report(rs.getObject("id", UUID.class),
                        rs.getString("reason"), rs.getString("note"), rs.getString("status"),
                        instant(rs, "created_at"), rs.getObject("other_id", UUID.class), rs.getString("other_name"),
                        (Integer) rs.getObject("other_hue", Integer.class), rs.getString("table_name"), rs.getString("message_snapshot")), userId);
    }

    private People people(UUID userId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM follows WHERE follower_id = ?) AS following,
                       (SELECT count(*) FROM follows WHERE followee_id = ?) AS followers,
                       (SELECT count(*) FROM follows a JOIN follows b
                          ON b.follower_id = a.followee_id AND b.followee_id = a.follower_id
                        WHERE a.follower_id = ?) AS friends,
                       (SELECT count(*) FROM blocks WHERE blocker_id = ?) AS blocking,
                       (SELECT count(*) FROM blocks WHERE blocked_id = ?) AS blocked_by
                """, (rs, row) -> new People(rs.getInt("following"), rs.getInt("followers"), rs.getInt("friends"),
                        rs.getInt("blocking"), rs.getInt("blocked_by")), userId, userId, userId, userId, userId);
    }

    private List<TableRow> tables(UUID userId) {
        return jdbc.query("""
                SELECT t.id, t.name, t.emoji, m.role, m.joined_at, t.archived_at IS NOT NULL AS archived,
                       (SELECT count(*) FROM table_members x WHERE x.table_id = t.id) AS members,
                       (SELECT count(*) FROM chat_messages c WHERE c.table_id = t.id AND c.author_id = m.user_id
                          AND c.kind IN ('text', 'gif')) AS messages,
                       (SELECT count(*) FROM chat_messages c WHERE c.table_id = t.id AND c.author_id = m.user_id
                          AND c.kind = 'poll') AS polls,
                       (SELECT count(*) FROM bets b WHERE b.table_id = t.id AND b.proposer_id = m.user_id) AS bets,
                       (SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id
                        WHERE b.table_id = t.id AND p.user_id = m.user_id AND b.status <> 'ACTIVE') AS picks,
                       COALESCE((SELECT sum(delta_cents) FROM ledger_entries l
                                 WHERE l.table_id = t.id AND l.user_id = m.user_id), 0) AS net
                FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.user_id = ? ORDER BY m.joined_at DESC
                """, (rs, row) -> new TableRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("emoji"), rs.getString("role"), instant(rs, "joined_at"),
                        rs.getBoolean("archived"), rs.getInt("members"), rs.getInt("messages"), rs.getInt("polls"),
                        rs.getInt("bets"), rs.getInt("picks"), rs.getLong("net")), userId);
    }

    private Betting betting(UUID userId) {
        // won/lost only on settled bets with a winning choice; 4fun kept apart; washes counted on their own.
        List<ModeRow> byMode = jdbc.query("""
                SELECT b.mode,
                       count(*) FILTER (WHERE b.status = 'SETTLED' AND NOT b.is_4fun
                                        AND p.choice = b.winning_choice) AS won,
                       count(*) FILTER (WHERE b.status = 'SETTLED' AND NOT b.is_4fun
                                        AND b.winning_choice IS NOT NULL AND p.choice <> b.winning_choice) AS lost,
                       count(*) FILTER (WHERE b.status = 'WASHED') AS washed,
                       count(*) FILTER (WHERE b.status = 'PENDING') AS open
                FROM picks p JOIN bets b ON b.id = p.bet_id WHERE p.user_id = ? AND b.status <> 'ACTIVE'
                GROUP BY b.mode ORDER BY count(*) DESC, b.mode
                """, (rs, row) -> new ModeRow(rs.getString("mode"), rs.getInt("won"), rs.getInt("lost"),
                        rs.getInt("washed"), rs.getInt("open")), userId);
        return jdbc.queryForObject("""
                WITH mine AS (SELECT b.*, p.choice FROM picks p JOIN bets b ON b.id = p.bet_id
                              WHERE p.user_id = ? AND b.status <> 'ACTIVE'),
                     per_bet AS (SELECT bet_id, sum(delta_cents)::bigint AS delta FROM ledger_entries
                                 WHERE user_id = ? GROUP BY bet_id)
                SELECT (SELECT count(*) FROM bets WHERE proposer_id = ?) AS proposed,
                       (SELECT count(*) FROM mine) AS picks,
                       (SELECT count(*) FROM mine WHERE status = 'PENDING') AS pending,
                       (SELECT count(*) FROM mine WHERE status = 'SETTLED' AND NOT is_4fun
                          AND choice = winning_choice) AS won,
                       (SELECT count(*) FROM mine WHERE status = 'SETTLED' AND NOT is_4fun
                          AND winning_choice IS NOT NULL AND choice <> winning_choice) AS lost,
                       (SELECT count(*) FROM mine WHERE status = 'WASHED') AS washed,
                       (SELECT count(*) FROM mine WHERE status = 'SETTLED' AND is_4fun
                          AND choice = winning_choice) AS fun_won,
                       (SELECT count(*) FROM mine WHERE status = 'SETTLED' AND is_4fun
                          AND winning_choice IS NOT NULL AND choice <> winning_choice) AS fun_lost,
                       COALESCE((SELECT sum(delta) FROM per_bet), 0) AS net,
                       (SELECT max(delta) FROM per_bet WHERE delta > 0) AS biggest_win,
                       (SELECT min(delta) FROM per_bet WHERE delta < 0) AS biggest_loss
                """, (rs, row) -> {
                    int won = rs.getInt("won");
                    int lost = rs.getInt("lost");
                    return new Betting(rs.getInt("proposed"), rs.getInt("picks"), rs.getInt("pending"), new Record(won, lost), rs.getInt("washed"),
                            new Record(rs.getInt("fun_won"), rs.getInt("fun_lost")),
                            won + lost == 0 ? null : (double) won / (won + lost), rs.getLong("net"),
                            (Long) rs.getObject("biggest_win", Long.class),
                            (Long) rs.getObject("biggest_loss", Long.class), byMode);
                }, userId, userId, userId);
    }

    private Money money(UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS settles,
                       COALESCE(sum(amount_cents) FILTER (WHERE payer_id = ? AND kind IN ('sent', 'received')
                                                          AND rejected_at IS NULL), 0) AS paid,
                       COALESCE(sum(amount_cents) FILTER (WHERE payee_id = ? AND kind IN ('sent', 'received')
                                                          AND rejected_at IS NULL), 0) AS collected,
                       count(*) FILTER (WHERE kind = 'sent' AND sender_id = ? AND rejected_at IS NOT NULL)
                           AS sends_rejected,
                       count(*) FILTER (WHERE kind = 'sent' AND other_id = ? AND rejected_at IS NOT NULL)
                           AS rejected_theirs,
                       count(*) FILTER (WHERE kind = 'sent' AND rejected_at IS NULL AND reject_by > now())
                           AS pending_window,
                       (SELECT cap_cents FROM debt_caps WHERE user_id = ?) AS cap
                FROM settles WHERE sender_id = ? OR other_id = ?
                """, (rs, row) -> new Money(rs.getInt("settles"), rs.getLong("paid"), rs.getLong("collected"),
                        rs.getInt("sends_rejected"),
                        rs.getInt("rejected_theirs"), rs.getInt("pending_window"),
                        (Integer) rs.getObject("cap", Integer.class)),
                userId, userId, userId, userId, userId, userId, userId);
    }

    /** One row per New York day for the last {@link #ACTIVITY_DAYS} days, oldest first, empty days included. */
    private List<Day> activity(UUID userId) {
        return jdbc.query("""
                WITH days AS (
                    SELECT generate_series((now() AT TIME ZONE 'America/New_York')::date - (? - 1),
                                           (now() AT TIME ZONE 'America/New_York')::date, interval '1 day')::date AS day
                ), since AS (SELECT (min(day)::timestamp AT TIME ZONE 'America/New_York') AS at FROM days),
                messages AS (
                    SELECT (created_at AT TIME ZONE 'America/New_York')::date AS day, count(*) AS n FROM chat_messages
                    WHERE author_id = ? AND created_at >= (SELECT at FROM since) GROUP BY 1
                ), picks AS (
                    SELECT (p.picked_at AT TIME ZONE 'America/New_York')::date AS day, count(*) AS n
                    FROM picks p JOIN bets b ON b.id = p.bet_id
                    WHERE p.user_id = ? AND b.status <> 'ACTIVE' AND p.picked_at >= (SELECT at FROM since) GROUP BY 1
                ), bets AS (
                    SELECT (created_at AT TIME ZONE 'America/New_York')::date AS day, count(*) AS n FROM bets
                    WHERE proposer_id = ? AND created_at >= (SELECT at FROM since) GROUP BY 1
                ), settles AS (
                    SELECT (created_at AT TIME ZONE 'America/New_York')::date AS day, count(*) AS n FROM settles
                    WHERE (sender_id = ? OR other_id = ?) AND created_at >= (SELECT at FROM since) GROUP BY 1
                )
                SELECT d.day, COALESCE(m.n, 0) AS messages, COALESCE(p.n, 0) AS picks, COALESCE(b.n, 0) AS bets,
                       COALESCE(s.n, 0) AS settles
                FROM days d LEFT JOIN messages m USING (day) LEFT JOIN picks p USING (day)
                     LEFT JOIN bets b USING (day) LEFT JOIN settles s USING (day)
                ORDER BY d.day
                """, (rs, row) -> new Day(rs.getObject("day", LocalDate.class), rs.getInt("messages"),
                        rs.getInt("picks"), rs.getInt("bets"), rs.getInt("settles")),
                ACTIVITY_DAYS, userId, userId, userId, userId, userId);
    }

    /**
     * What the player wrote, newest first: chat messages and GIFs ({@code message}), polls ({@code poll}) and the
     * bets they proposed ({@code bet}), optionally only one kind and only those containing {@code query}
     * (case-insensitive, in the text, poll options, or bet question and choices).
     */
    @Transactional(readOnly = true)
    Activity activity(UUID userId, String kind, String query, String cursor) {
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null) {
            List<String> parts = PageCursor.decode(cursor, 2);
            before = OffsetDateTime.parse(parts.get(0)).toInstant();
            beforeId = UUID.fromString(parts.get(1));
        }
        String like = query == null || query.isBlank() ? null : "%" + escape(query.trim()) + "%";
        boolean messages = kind == null || kind.equals("message");
        boolean polls = kind == null || kind.equals("poll");
        boolean bets = kind == null || kind.equals("bet");
        Timestamp beforeAt = before == null ? null : Timestamp.from(before);
        List<Item> items = jdbc.query("""
                SELECT * FROM (
                    SELECT CASE WHEN c.kind = 'poll' THEN 'poll' ELSE 'message' END AS kind, c.id, c.table_id,
                           t.name AS table_name, COALESCE(c.text, 'GIF: ' || c.gif_id) AS text,
                           c.poll_options AS options, NULL AS mode, NULL AS status, NULL::bigint AS wager,
                           c.created_at
                    FROM chat_messages c JOIN tables t ON t.id = c.table_id
                    WHERE c.author_id = ?
                      AND ((? AND c.kind IN ('text', 'gif')) OR (? AND c.kind = 'poll'))
                      AND (?::text IS NULL OR c.text ILIKE ? OR array_to_string(c.poll_options, ' ') ILIKE ?)
                    UNION ALL
                    SELECT 'bet', b.id, b.table_id, t.name, b.question, ARRAY(SELECT jsonb_array_elements_text(b.choices)),
                           b.mode, b.status, CASE WHEN b.is_4fun THEN NULL ELSE b.wager_cents END, b.created_at
                    FROM bets b JOIN tables t ON t.id = b.table_id
                    WHERE b.proposer_id = ? AND ?
                      AND (?::text IS NULL OR b.question ILIKE ? OR b.choices::text ILIKE ?)
                ) items
                WHERE (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new Item(rs.getString("kind"), rs.getObject("id", UUID.class),
                        rs.getObject("table_id", UUID.class), rs.getString("table_name"), rs.getString("text"),
                        options(rs), rs.getString("mode"), rs.getString("status"),
                        (Long) rs.getObject("wager", Long.class), instant(rs, "created_at")),
                userId, messages, polls, like, like, like, userId, bets, like, like, like, beforeAt, beforeAt,
                beforeId, ACTIVITY_PAGE + 1);
        String next = null;
        if (items.size() > ACTIVITY_PAGE) {
            items = new ArrayList<>(items.subList(0, ACTIVITY_PAGE));
            Item last = items.getLast();
            next = PageCursor.encode(last.createdAt().toString(), last.id());
        }
        return new Activity(items, next);
    }

    private static List<String> options(ResultSet rs) throws SQLException {
        java.sql.Array array = rs.getArray("options");
        return array == null ? null : Arrays.asList((String[]) array.getArray());
    }

    /** Escapes LIKE wildcards so a search for "50%" matches the text "50%". */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

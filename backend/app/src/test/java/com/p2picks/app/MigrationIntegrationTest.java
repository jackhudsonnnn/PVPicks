package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class MigrationIntegrationTest extends IntegrationTestSupport {
    @Test
    void migratesAndEnforcesUserIdentityAndDeletionConstraints() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "sample_user", 1, "test-hash", "PLAYER", "ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT id FROM users WHERE username = ?::citext", UUID.class, "SAMPLE_USER"))
                .isEqualTo(userId);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), "sample_user", 1, "test-hash", "PLAYER", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET username = ? WHERE id = ?", "Bad Name", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET hue = NULL WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET status = 'DELETED', deleted_at = now() WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(
                        "UPDATE users SET username = NULL, hue = NULL, password_hash = NULL, status = 'DELETED', deleted_at = now() WHERE id = ?",
                        userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ? AND username IS NULL", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void storesSessionsWithValidLifetimeAndExistingUser() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "session_user", 1, "test-hash", "PLAYER", "ACTIVE");
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sessionId, userId, now, now.plusHours(8));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_sessions WHERE id = ? AND revoked_at IS NULL",
                        Integer.class, sessionId))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), userId, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), UUID.randomUUID(), now, now.plusHours(8)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void socialRelationshipsAreDirectedUniqueAndCannotReferenceSelfOrMissingUsers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'social_first', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'social_second', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, first, second);

        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, second);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", second, first);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, second))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM follows WHERE follower_id IN (?, ?)", first, second);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, second);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", second, first);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, second))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tablesSupportMultipleHostsAndRetainUniqueQuickPickCodes() {
        UUID tableId = UUID.randomUUID();
        UUID firstHost = UUID.randomUUID();
        UUID secondHost = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'table_host1', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'table_host2', 2, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'table_member', 3, 'test-hash', 'PLAYER', 'ACTIVE')
                """, firstHost, secondHost, member);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Test table", "T", "K4R7QX");
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role)
                VALUES (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, tableId, firstHost, tableId, secondHost, tableId, member);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM table_members WHERE table_id = ? AND role = 'HOST'",
                        Integer.class, tableId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, tableId))
                .isEqualTo("K4R7QX");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), "Duplicate", "T", "K4R7QX"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE tables SET quickpick_code = 'INVALID' WHERE id = ?", tableId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE tables SET quickpick_sharing = true WHERE id = ?", tableId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?",
                        tableId, firstHost))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                        tableId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tableInvitationsAllowOnlyOnePendingInvitePerRecipient() {
        UUID tableId = UUID.randomUUID();
        UUID inviter = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'invite_host', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'invite_target', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, inviter, recipient);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Invite table", "T", "N4R7QX");
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                inviteId, tableId, inviter, recipient);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, recipient))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, inviter))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE table_invites SET status = 'DECLINED' WHERE id = ?", inviteId);
        assertThat(jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, recipient))
                .isEqualTo(1);
    }

    @Test
    void quickPickSubscriptionsAreDirectedUniqueAndReferenceDifferentTables() {
        UUID source = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Source', 'T', 'Q4R7QX'), (?, 'Subscriber', 'T', 'R4R7QX')
                """, source, subscriber);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriber, source);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                source, subscriber);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        subscriber, source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        source, source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        subscriber, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxAndDomainChangesCommitOrRollBackTogether() {
        UUID source = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Outbox source', 'T', 'S4R7QX'), (?, 'Outbox subscriber', 'T', 'T4R7QX')
                """, source, subscriber);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                    subscriber, source);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                    """, eventId, subscriber.toString());
            throw new IllegalStateException("rollback test");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, eventId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriber, source))
                .isZero();

        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                    subscriber, source);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'table', ?, 'example.v1', '{"version": 1}'::jsonb)
                    """, eventId, subscriber.toString());
        });
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'version' FROM outbox WHERE id = ? AND published_at IS NULL",
                        String.class, eventId))
                .isEqualTo("1");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriber, source))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                        """, eventId, subscriber.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void betParticipationKeepsNoGuessSeparateAndSnapshotsTheHostAtLock() {
        UUID userId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID betId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'bet_host', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Bets", "T", "X4R7QX");
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Next play?', '["Pass","Run"]'::jsonb,
                        100, false, now())
                """, betId, tableId, userId);
        assertThatThrownBy(() -> jdbc.update("UPDATE bets SET wager_cents = 9 WHERE id = ?", betId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bets SET wager_cents = 1001 WHERE id = ?", betId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE bets SET wager_cents = 10 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET wager_cents = 1000 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET is_4fun = true, wager_cents = 0 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET is_4fun = false, wager_cents = 100 WHERE id = ?", betId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?",
                        Integer.class, betId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Pass')", betId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", betId, userId);
        Long order = jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Pass')", betId, userId);
        jdbc.update("DELETE FROM picks WHERE bet_id = ? AND user_id = ?", betId, userId);
        assertThat(jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId)).isEqualTo(order);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, '__no_guess__')", betId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Run')", betId, userId);
        assertThat(jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId)).isEqualTo(order);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'host' WHERE id = ?
                """, betId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE bets SET remainder_rule = 'host', remainder_host_id = ? WHERE id = ?
                """, userId, betId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(),
                                remainder_rule = 'host', remainder_host_id = ? WHERE id = ?
                """, userId, betId);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, betId))
                .isEqualTo(userId);
    }

    @Test
    void quickPickCopiesReferenceTheirActualSourceAndAreUniqueAtTheTarget() {
        UUID userId = UUID.randomUUID();
        UUID sourceTable = UUID.randomUUID();
        UUID targetTable = UUID.randomUUID();
        UUID unrelatedTable = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'copy_author', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Source bet', 'T', 'Y4R7QX'), (?, 'Target bet', 'T', 'Z4R7QX'),
                       (?, 'Unrelated', 'T', '234567')
                """, sourceTable, targetTable, unrelatedTable);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Next play?', '["Pass","Run"]'::jsonb,
                        100, false, now())
                """, original, sourceTable, userId);
        String insertCopy = """
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at, source_bet_id, source_table_id)
                SELECT ?, ?, proposer_id, mode, config, question, choices,
                       wager_cents, is_4fun, locks_at, ?, ?
                FROM bets WHERE id = ?
                """;
        jdbc.update(insertCopy, UUID.randomUUID(), targetTable, original, sourceTable, original);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, sourceTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, unrelatedTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), sourceTable, original, sourceTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, null, original))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settlementHistoryRetainsLineIdentityAndBetAssociations() {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        UUID laterSettlement = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'settle_host', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'settle_member', 2, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'settle_other', 3, 'test-hash', 'PLAYER', 'ACTIVE')
                """, host, member, other);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Settle', 'T', '345678'), (?, 'Other settle', 'T', '456789')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?), (?, ?, ?)
                """, settlement, table, host, laterSettlement, table, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now())
                """, bet, table, host);
        jdbc.update("INSERT INTO settlement_bets (bet_id, table_id, settlement_id) VALUES (?, ?, ?)",
                bet, table, settlement);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO settlement_bets (bet_id, table_id, settlement_id) VALUES (?, ?, ?)",
                        bet, table, laterSettlement))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM settlement_bets WHERE bet_id = ?", bet))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET host_id = ? WHERE id = ?", member, settlement))
                .isInstanceOf(DataIntegrityViolationException.class);

        String insertLine = """
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(insertLine, line, settlement, table, host, member, host, 100);
        jdbc.update(insertLine, UUID.randomUUID(), laterSettlement, table, host, host, member, 50);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, host, member, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, member, other, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, otherTable, host, other, host, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, other, host, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE settlement_lines SET status = 'sent', revision = 1, updated_at = now() WHERE id = ?", line);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("sent");
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_lines SET revision = -1 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_lines SET status = 'unknown' WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE settlement_lines SET status = 'forgive_asked', forgive_requested_at = now(),
                                            revision = 2, updated_at = now() WHERE id = ?
                """, line);
        assertThat(jdbc.queryForObject(
                        "SELECT forgive_requested_at FROM settlement_lines WHERE id = ?", OffsetDateTime.class, line))
                .isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE settlement_lines SET amount_cents = 200 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlement_lines WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void debtCapsStoreOffZeroAndPositiveValuesWithoutAllowingNegativeCaps() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'cap_user', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, user);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, NULL)", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isNull();
        jdbc.update("UPDATE debt_caps SET cap_cents = 0 WHERE user_id = ?", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isZero();
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isEqualTo(100);
        assertThatThrownBy(() -> jdbc.update("UPDATE debt_caps SET cap_cents = -1 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradesPopulatedVersionTwoThroughTwentyFiveDatabases() {
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        Flyway initial = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("2")).load();
        assertThat(initial.migrate().migrationsExecuted).isEqualTo(2);
        JdbcTemplate upgradedJdbc = new JdbcTemplate(
                new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        UUID userId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'existing_user', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);

        Flyway social = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("3")).load();
        assertThat(social.migrate().migrationsExecuted).isEqualTo(1);
        UUID secondUserId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'second_user', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, secondUserId);
        upgradedJdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", userId, secondUserId);

        Flyway tables = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("4")).load();
        assertThat(tables.migrate().migrationsExecuted).isEqualTo(1);
        UUID tableId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Existing table", "T", "V4R7QX");
        upgradedJdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')",
                tableId, userId);

        Flyway subscriptions = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("5")).load();
        assertThat(subscriptions.migrate().migrationsExecuted).isEqualTo(1);
        UUID subscriberId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                subscriberId, "Existing subscriber", "T", "W4R7QX");
        upgradedJdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriberId, tableId);

        Flyway outbox = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("6")).load();
        assertThat(outbox.migrate().migrationsExecuted).isEqualTo(1);
        UUID eventId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                """, eventId, tableId.toString());

        Flyway bets = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("7")).load();
        assertThat(bets.migrate().migrationsExecuted).isEqualTo(1);
        UUID betId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now())
                """, betId, tableId, userId);

        Flyway ledger = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("8")).load();
        assertThat(ledger.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO ledger_entries (id, bet_id, table_id, user_id, delta_cents)
                VALUES (?, ?, ?, ?, 100), (?, ?, ?, ?, -100)
                """, UUID.randomUUID(), betId, tableId, userId,
                UUID.randomUUID(), betId, tableId, secondUserId);

        Flyway settlements = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("9")).load();
        assertThat(settlements.migrate().migrationsExecuted).isEqualTo(1);
        UUID settlementId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)",
                settlementId, tableId, userId);

        Flyway chat = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("10")).load();
        assertThat(chat.migrate().migrationsExecuted).isEqualTo(1);
        UUID messageId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Existing message')
                """, messageId, tableId, userId);

        Flyway notifications = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("11")).load();
        assertThat(notifications.migrate().migrationsExecuted).isEqualTo(1);
        UUID notificationId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone, title)
                VALUES (?, ?, ?, 'existing', 'invite', 'Existing notification')
                """, notificationId, userId, UUID.randomUUID());

        Flyway feedback = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("12")).load();
        assertThat(feedback.migrate().migrationsExecuted).isEqualTo(1);
        UUID feedbackId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO feedback (id, user_id, kind, text)
                VALUES (?, ?, 'bug', 'Existing feedback')
                """, feedbackId, userId);

        Flyway adminFlags = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("13")).load();
        assertThat(adminFlags.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("INSERT INTO admin_user_flags (user_id, flagged, strike_count) VALUES (?, true, 2)",
                userId);

        Flyway processedEvents = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("14")).load();
        assertThat(processedEvents.migrate().migrationsExecuted).isEqualTo(1);
        UUID processedEventId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO processed_events (consumer_name, event_id) VALUES ('test', ?)",
                processedEventId);

        Flyway archives = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("15")).load();
        assertThat(archives.migrate().migrationsExecuted).isEqualTo(1);
        UUID archivedId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at)
                VALUES (?, 'Archived', 'T', 'V5R7QX', now())
                """, archivedId);
        upgradedJdbc.update("INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", archivedId, userId);

        Flyway retries = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("16")).load();
        assertThat(retries.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status)
                VALUES (?, decode(repeat('00', 32), 'hex'), decode(repeat('11', 32), 'hex'), 'existing', 204)
                """, userId);

        Flyway invitationPreferences = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("17")).load();
        assertThat(invitationPreferences.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("INSERT INTO invite_preferences (user_id, friends, following) VALUES (?, 'closed', 'open')",
                userId);
        UUID legacyFeedback = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO feedback (id, user_id, kind, text, link)
                VALUES (?, ?, 'gif', 'Existing feedback', ?)
                """, legacyFeedback, userId, "https://x/\uFEFFy");

        Flyway feedbackLinks = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("18")).load();
        assertThat(feedbackLinks.migrate().migrationsExecuted).isEqualTo(1);
        UUID validInvite = UUID.randomUUID();
        UUID staleInvite = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')",
                subscriberId, secondUserId);
        upgradedJdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, validInvite, subscriberId, secondUserId, userId, staleInvite, tableId, secondUserId, userId);
        Flyway invitations = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("19")).load();
        assertThat(invitations.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO chat_read_positions (table_id, user_id, last_read_order)
                SELECT table_id, ?, message_order FROM chat_messages WHERE id = ?
                """, secondUserId, messageId);
        UUID legacyChatNotice = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone,
                                           title, table_id, actor_id, source_message_id)
                VALUES (?, ?, ?, 'legacy-chat', 'chat', 'Chat', ?, ?, ?)
                """, legacyChatNotice, secondUserId, UUID.randomUUID(), tableId, userId, messageId);
        Flyway readState = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("20")).load();
        assertThat(readState.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id = ?",
                Boolean.class, legacyChatNotice)).isTrue();
        UUID delayedChatNotice = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone,
                                           title, table_id, actor_id, source_message_id)
                VALUES (?, ?, ?, 'delayed-chat', 'chat', 'Chat', ?, ?, ?)
                """, delayedChatNotice, secondUserId, UUID.randomUUID(), tableId, userId, messageId);
        assertThat(upgradedJdbc.queryForObject("SELECT read_at IS NOT NULL FROM notifications WHERE id = ?",
                Boolean.class, delayedChatNotice)).isTrue();
        Flyway retryReferences = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("21")).load();
        assertThat(retryReferences.migrate().migrationsExecuted).isEqualTo(1);
        UUID legacySystem = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'Existing system line')
                """, legacySystem, tableId);
        Flyway hostAttribution = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("22")).load();
        assertThat(hostAttribution.migrate().migrationsExecuted).isEqualTo(1);
        Flyway updated = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load();
        assertThat(updated.migrate().migrationsExecuted).isEqualTo(5); // V23 through V27
        assertThatThrownBy(() -> upgradedJdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgradedJdbc.queryForObject("""
                SELECT text FROM chat_messages WHERE id = ? AND system_event IS NULL AND system_subject_id IS NULL
                """, String.class, legacySystem)).isEqualTo("Existing system line");
        assertThat(upgradedJdbc.queryForObject(
                "SELECT count(*) FROM http_idempotency WHERE response_resource_id IS NOT NULL", Integer.class)).isZero();
        assertThat(upgradedJdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, validInvite))
                .isEqualTo("PENDING");
        assertThat(upgradedJdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, staleInvite))
                .isEqualTo("CANCELLED");
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject(
                        "SELECT count(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
                        Integer.class, userId, secondUserId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM blocks", Integer.class)).isZero();
        assertThat(upgradedJdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?",
                        String.class, tableId))
                .isEqualTo("V4R7QX");
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ?",
                        Integer.class, tableId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriberId, tableId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM bets WHERE id = ?", Integer.class, betId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?",
                        Integer.class, betId)).isEqualTo(2);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM settlements WHERE id = ?",
                        Integer.class, settlementId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE id = ?",
                        Integer.class, messageId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notifications')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notification_preferences')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notification_muted_tables')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM chat_read_positions WHERE table_id = ? AND user_id = ?",
                Integer.class, tableId, secondUserId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM feedback WHERE id = ?",
                        Integer.class, feedbackId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT link FROM feedback WHERE id = ?", String.class, legacyFeedback))
                .isEqualTo("https://x/\uFEFFy");
        assertThat(upgradedJdbc.queryForObject("SELECT strike_count FROM admin_user_flags WHERE user_id = ?",
                        Integer.class, userId)).isEqualTo(2);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, processedEventId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM tables WHERE id = ? AND archived_at IS NULL",
                        Integer.class, tableId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject(
                "SELECT count(*) FROM table_archive_members WHERE table_id = ? AND user_id = ?",
                Integer.class, archivedId, userId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM http_idempotency", Integer.class)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT friends FROM invite_preferences WHERE user_id = ?",
                String.class, userId)).isEqualTo("closed");
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status)
                VALUES (?, decode(repeat('22', 32), 'hex'), decode(repeat('33', 32), 'hex'), 'created', 201)
                """, userId);
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status, response_data)
                VALUES (?, decode(repeat('44', 32), 'hex'), decode(repeat('55', 32), 'hex'), 'members', 200, '[]'::jsonb)
                """, userId);
        assertThat(updated.migrate().migrationsExecuted).isZero();
    }
}

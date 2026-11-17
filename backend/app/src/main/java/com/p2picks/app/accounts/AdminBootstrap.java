package com.p2picks.app.accounts;

import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.AccountSetupCodes;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first admin account when {@code PVPICKS_BOOTSTRAP_ADMIN} names one (LAN plan, "Accounts and
 * passwords"), since without the demo world nobody could sign in. On every start while that admin hasn't set a
 * password, it issues a fresh setup code and logs the one-time setup link: a single-use, 24-hour secret, in the
 * local logs only. Once the password is set it does nothing.
 */
@Component
@ConditionalOnExpression("!'${pvpicks.bootstrap-admin.username:}'.isBlank()")
class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final AccountSetupCodes codes;
    private final TransactionTemplate transaction;
    private final String username;
    private final String publicUrl;

    AdminBootstrap(JdbcTemplate jdbc, AccountSetupCodes codes, TransactionTemplate transaction,
            @Value("${pvpicks.bootstrap-admin.username}") String username,
            @Value("${pvpicks.public-url:}") String publicUrl) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.transaction = transaction;
        this.username = PostgresText.trim(username).toLowerCase(Locale.ROOT);
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!username.matches("[a-z0-9_]{3,15}")) {
            throw new IllegalStateException("PVPICKS_BOOTSTRAP_ADMIN must be 3 to 15 letters, numbers or underscores");
        }
        AccountSetupCodes.Issued issued = transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 4, ?, 'ADMIN', 'ACTIVE') ON CONFLICT (username) DO NOTHING
                    """, UUID.randomUUID(), username, AccountSetupCodes.unusablePasswordHash());
            List<Account> accounts = jdbc.query("""
                    SELECT id, role, password_hash FROM users WHERE username = ?::citext FOR UPDATE
                    """, (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("role"),
                    rs.getString("password_hash")), username);
            Account account = accounts.getFirst();
            if (!account.role().equals("ADMIN")) {
                throw new IllegalStateException("PVPICKS_BOOTSTRAP_ADMIN names a player account, not an admin");
            }
            if (account.passwordHash().startsWith("$2")) return null;
            return codes.issue(account.id(), AccountSetupCodes.Purpose.SETUP, null);
        });
        if (issued == null) return;
        log.warn("Admin \"{}\" has no password yet. Open this one-time link within 24 hours to set it: {}/setup#{}",
                username, publicUrl, issued.code());
    }

    private record Account(UUID id, String role, String passwordHash) {}
}

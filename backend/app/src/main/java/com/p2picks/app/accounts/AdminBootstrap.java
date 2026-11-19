package com.p2picks.app.accounts;

import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Creates the LAN admin when {@code PVPICKS_BOOTSTRAP_ADMIN} names one, with the password in
 * {@code PVPICKS_BOOTSTRAP_ADMIN_PASSWORD} (the admin portal's "special password"; it must meet the password policy).
 * Without the demo world nobody else could sign in. The password is only set while the admin has none, so a password
 * changed later in the app survives restarts.
 */
@Component
@ConditionalOnExpression("!'${pvpicks.bootstrap-admin.username:}'.isBlank()")
class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final String username;
    private final String password;

    AdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords, TransactionTemplate transaction,
            @Value("${pvpicks.bootstrap-admin.username}") String username,
            @Value("${pvpicks.bootstrap-admin.password:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.transaction = transaction;
        this.username = PostgresText.trim(username).toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!username.matches("[a-z0-9_]{3,15}")) {
            throw new IllegalStateException("PVPICKS_BOOTSTRAP_ADMIN must be 3 to 15 letters, numbers or underscores");
        }
        PasswordPolicy.problem(username, password).ifPresent(problem -> {
            throw new IllegalStateException("PVPICKS_BOOTSTRAP_ADMIN_PASSWORD: " + problem);
        });
        boolean set = Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 4, '!unset', 'ADMIN', 'ACTIVE') ON CONFLICT (username) DO NOTHING
                    """, UUID.randomUUID(), username);
            List<Account> accounts = jdbc.query("""
                    SELECT id, role, password_hash FROM users WHERE username = ?::citext FOR UPDATE
                    """, (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("role"),
                    rs.getString("password_hash")), username);
            Account account = accounts.getFirst();
            if (!account.role().equals("ADMIN")) {
                throw new IllegalStateException("PVPICKS_BOOTSTRAP_ADMIN names a player account, not an admin");
            }
            if (account.passwordHash().startsWith("$2")) return false;
            jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwords.encode(password), account.id());
            return true;
        }));
        if (set) log.info("Admin \"{}\" created; sign in with PVPICKS_BOOTSTRAP_ADMIN_PASSWORD", username);
    }

    private record Account(UUID id, String role, String passwordHash) {}
}

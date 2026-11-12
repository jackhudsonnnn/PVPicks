package com.p2picks.app;

import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableQuickPickService {
    enum Action { ENABLE, DISABLE, SHARE, STOP_SHARING, ROTATE }
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Result(Outcome outcome, String code) {}
    private record Settings(boolean enabled, boolean sharing, String code) {}
    static class FeatureDisabled extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SystemChatWriter systemChat;
    private final QuickPickSubscriptions subscriptions;

    TableQuickPickService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, SystemChatWriter systemChat, QuickPickSubscriptions subscriptions) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.systemChat = systemChat;
        this.subscriptions = subscriptions;
    }

    @Transactional
    public Result change(Jwt jwt, UUID tableId, Action action, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        List<Settings> tables = jdbc.query("""
                SELECT quickpicks_enabled, quickpick_sharing, quickpick_code FROM tables
                WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Settings(rs.getBoolean("quickpicks_enabled"),
                        rs.getBoolean("quickpick_sharing"), rs.getString("quickpick_code")), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.NOT_FOUND, null);
        }
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, userId);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
        if (!roles.getFirst().equals("HOST")) return new Result(Outcome.FORBIDDEN, null);

        IdempotencyGuard.SuccessStatus status = action == Action.ROTATE
                ? IdempotencyGuard.SuccessStatus.OK : IdempotencyGuard.SuccessStatus.NO_CONTENT;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "table.quickpicks.change.v1",
                tableId + "|" + action, status);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        Settings current = tables.getFirst();
        if (claim == IdempotencyGuard.Claim.REPLAY) return new Result(Outcome.REPLAYED, current.code());

        if (action == Action.SHARE && !current.enabled()) throw new FeatureDisabled();
        boolean changed = switch (action) {
            case ENABLE -> !current.enabled();
            case DISABLE -> current.enabled();
            case SHARE -> !current.sharing();
            case STOP_SHARING -> current.sharing();
            case ROTATE -> true;
        };
        String code = current.code();
        if (changed) {
            if (action == Action.DISABLE || action == Action.STOP_SHARING || action == Action.ROTATE) {
                subscriptions.disconnect(tableId, action == Action.DISABLE);
            }
            switch (action) {
                case ENABLE -> jdbc.update("""
                        UPDATE tables SET quickpicks_enabled = true, quickpick_sharing = true WHERE id = ?
                        """, tableId);
                case DISABLE -> jdbc.update("""
                        UPDATE tables SET quickpicks_enabled = false, quickpick_sharing = false WHERE id = ?
                        """, tableId);
                case SHARE -> jdbc.update("UPDATE tables SET quickpick_sharing = true WHERE id = ?", tableId);
                case STOP_SHARING -> jdbc.update("UPDATE tables SET quickpick_sharing = false WHERE id = ?", tableId);
                case ROTATE -> code = rotate(tableId, current.code());
            }
            systemChat.write(tableId, switch (action) {
                case ENABLE -> "Quick Picks turned on";
                case DISABLE -> "Quick Picks turned off";
                case SHARE -> "Quick Pick sharing turned on";
                case STOP_SHARING -> "Quick Pick sharing turned off";
                case ROTATE -> "Quick Pick code changed";
            });
            subscriptions.changedEvent(tableId);
        }
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.UPDATED, code);
    }

    private String rotate(UUID tableId, String previous) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = QuickPickCodes.generate();
            if (code.equals(previous)) continue;
            if (tryRotate(tableId, code)) return code;
        }
        throw new IllegalStateException("Could not allocate a unique Quick Pick code");
    }

    boolean tryRotate(UUID tableId, String code) {
        Boolean updated = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("UPDATE tables SET quickpick_code = ? WHERE id = ?")) {
                statement.setString(1, code);
                statement.setObject(2, tableId);
                int count = statement.executeUpdate();
                if (count != 1) throw new IllegalStateException("Locked table disappeared during code rotation");
                connection.releaseSavepoint(savepoint);
                return true;
            } catch (SQLException exception) {
                if (!"23505".equals(exception.getSQLState())) throw exception;
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                return false;
            }
        });
        if (updated == null) throw new IllegalStateException("Code rotation returned no result");
        return updated;
    }
}

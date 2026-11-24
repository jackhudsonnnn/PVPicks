package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Run it back" on a finished card game (owner decision, 2026-11-24): the first tap proposes the same game again (same
 * rules and stakes) with a 30-second join window, the tapper in it; everyone else who taps joins that one rematch. It
 * deals like any game, so with fewer than its minimum players when the window closes it's called off.
 *
 * <p>The finished game isn't locked: a unique index on {@code rematch_of} settles two first taps at once (the second
 * fails and is retried by the player, landing in the first one's rematch).
 */
@Service
public class CardGameRematch {
    static final int JOIN_WINDOW_SEC = 30;

    /** {@code betId} is the rematch (created or joined) when the outcome is DONE. */
    public record Result(CardGames.Outcome outcome, UUID betId, boolean created) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JackblackService jackblack;
    private final HoldemService holdem;

    CardGameRematch(JdbcTemplate jdbc, ObjectMapper json, JackblackService jackblack, HoldemService holdem) {
        this.jdbc = jdbc;
        this.json = json;
        this.jackblack = jackblack;
        this.holdem = holdem;
    }

    private record Source(UUID tableId, String mode, String status, long wagerCents, boolean forFun, String config) {}

    private record Rematch(UUID id, String status) {}

    @Transactional
    public Result runItBack(Jwt jwt, UUID betId, String key) throws Exception {
        List<Source> sources = jdbc.query("""
                SELECT table_id, mode, status, wager_cents, is_4fun, config::text AS config FROM bets
                WHERE id = ? AND mode IN ('jackblack', 'holdem')
                """, (rs, row) -> new Source(rs.getObject("table_id", UUID.class), rs.getString("mode"),
                        rs.getString("status"), rs.getLong("wager_cents"), rs.getBoolean("is_4fun"),
                        rs.getString("config")), betId);
        if (sources.isEmpty()) return new Result(CardGames.Outcome.NOT_FOUND, null, false);
        Source source = sources.getFirst();
        if (!source.status().equals("SETTLED") && !source.status().equals("WASHED")) {
            throw new CardGames.Refused("This game isn't over yet.");
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Rematch> rematches = jdbc.query("SELECT id, status FROM bets WHERE rematch_of = ?",
                (rs, row) -> new Rematch(rs.getObject("id", UUID.class), rs.getString("status")), betId);
        if (!rematches.isEmpty()) {
            Rematch rematch = rematches.getFirst();
            if (!rematch.status().equals("ACTIVE")) throw new CardGames.Refused("The rematch has already started.");
            // Already in (a retry, or the one who ran it back): nothing to do.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM card_players WHERE bet_id = ? AND user_id = ?)
                    """, Boolean.class, rematch.id(), userId))) {
                return new Result(CardGames.Outcome.DONE, rematch.id(), false);
            }
            CardGames.Outcome joined = source.mode().equals(JackblackService.MODE)
                    ? jackblack.join(jwt, rematch.id(), key) : holdem.join(jwt, rematch.id(), key);
            return new Result(joined, rematch.id(), false);
        }
        JsonNode config = json.readTree(source.config());
        BetProposalWriter.Result proposed = source.mode().equals(JackblackService.MODE)
                ? jackblack.propose(jwt, source.tableId(), JackblackRules.fromConfig(config), source.wagerCents(),
                        JOIN_WINDOW_SEC, source.forFun(), key)
                : holdem.propose(jwt, source.tableId(), HoldemRules.fromConfig(config), source.wagerCents(),
                        JOIN_WINDOW_SEC, source.forFun(), key);
        if (proposed.outcome() == BetProposalWriter.Outcome.CREATED) {
            jdbc.update("UPDATE bets SET rematch_of = ? WHERE id = ?", betId, proposed.betId());
        }
        return switch (proposed.outcome()) {
            case CREATED -> new Result(CardGames.Outcome.DONE, proposed.betId(), true);
            case REPLAYED -> new Result(CardGames.Outcome.REPLAYED, proposed.betId(), false);
            case CONFLICT -> new Result(CardGames.Outcome.CONFLICT, null, false);
            case UNAUTHORIZED -> new Result(CardGames.Outcome.UNAUTHORIZED, null, false);
            case FORBIDDEN -> new Result(CardGames.Outcome.FORBIDDEN, null, false);
            case NOT_FOUND -> new Result(CardGames.Outcome.NOT_FOUND, null, false);
        };
    }
}

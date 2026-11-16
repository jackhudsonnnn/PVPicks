package com.p2picks.app.sports;

import com.p2picks.app.common.ApiErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A game's pickable players with their current stats, and the league's stats, for proposing a player-mode bet.
 * Public game data only (no bets or picks), for any signed-in player.
 */
@RestController
class GamePlayersController {
    private final GameLookup games;
    private final PlayerStatsLookup players;

    GamePlayersController(GameLookup games, PlayerStatsLookup players) {
        this.games = games;
        this.players = players;
    }

    @GetMapping("/games/{gameId}/players")
    ResponseEntity<?> players(@PathVariable String gameId) {
        var game = games.find(gameId);
        if (game.isEmpty()) return ApiErrors.error(404, "not_found", "Game not found.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stats", StatLine.STATS.get(game.get().league()));
        body.put("players", players.players(gameId).stream().map(player -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", player.id());
            row.put("name", player.name());
            row.put("position", player.position());
            row.put("team", player.team());
            row.put("played", player.played());
            row.put("stats", player.stats());
            return row;
        }).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

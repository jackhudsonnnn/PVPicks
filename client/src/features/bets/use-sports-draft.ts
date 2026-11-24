import { useEffect, useState } from "react";
import type { Bet } from "@/features/bets/api";
import type { ModeDef } from "@/features/bets/modes";
import type { User } from "@/features/people";
import { type TickerGame, useGamePlayers, useTicker } from "@/features/sports";
import {
  DEFAULT_LINE,
  isPlayerMode,
  isScoreMode,
  isSportsMode,
  playerProblem,
  playerTerms,
  proposableGames,
  scoreTerms,
  suggestedLine,
  type PlayerMode,
  type ScoreMode,
  type SportsMode,
} from "@/features/bets/sports-model";

const LEAGUES: TickerGame["league"][] = ["NBA", "NFL"];

/**
 * A sports proposal being set up in the proposal sheet: the game (by league), the line or target, whether overtime
 * counts, and for the player modes the stat and players. It keeps the line legal as the game goes on (a live total
 * starts above the combined score; a player line above their number), fills in from a resent bet, and builds the
 * preview's terms and the proposal to send.
 */
export function useSportsDraft(
  user: User,
  mode: ModeDef | null,
  open: boolean,
  from: Bet | undefined,
) {
  const [gameId, setGameId] = useState<string | null>(null);
  const [league, setLeague] = useState<TickerGame["league"]>("NBA");
  const [line, setLine] = useState(0);
  const [overtime, setOvertime] = useState(true);
  const [stat, setStat] = useState<string | null>(null);
  const [playerIds, setPlayerIds] = useState<string[]>([]);
  const ticker = useTicker(user.id);
  const sportsMode: SportsMode | null = mode && isSportsMode(mode.id) ? mode.id : null;
  const scoreMode: ScoreMode | null = mode && isScoreMode(mode.id) ? mode.id : null;
  const playerMode: PlayerMode | null = mode && isPlayerMode(mode.id) ? mode.id : null;
  const games = proposableGames(ticker.data).filter((g) => LEAGUES.includes(g.league));
  const game = games.find((g) => g.id === gameId);
  // A resent bet's game that can't take new bets any more (it's over or postponed).
  const lostGame = gameId !== null && ticker.data !== undefined && !game;
  const roster = useGamePlayers(user.id, playerMode ? (game?.id ?? null) : null);
  const statKey = stat ?? roster.data?.stats[0]?.key ?? null;
  const picked = playerIds
    .map((id) => roster.data?.players.find((p) => p.id === id))
    .filter((p) => p !== undefined);
  const statLabel = roster.data?.stats.find((s) => s.key === statKey)?.label ?? "";

  // Resend: a finished sports bet's game, players, stat, line and overtime, once the sheet opens with it.
  useEffect(() => {
    if (!open || !from?.gameId || !isSportsMode(from.modeId)) return;
    setGameId(from.gameId);
    setOvertime(from.includesOvertime ?? true);
    const config = from.config;
    setStat(typeof config["stat"] === "string" ? config["stat"] : null);
    setPlayerIds(Array.isArray(config["players"]) ? config["players"] : []);
    if (config["line"] !== undefined) setLine(Number(config["line"]));
    // Only when a new bet is handed over.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, from?.id]);
  useEffect(() => {
    if (from?.gameId && game && game.id === from.gameId) setLeague(game.league);
  }, [from?.gameId, game]);

  // A live game's total line starts above the combined score (the server refuses one it has already passed) and
  // follows the score up while you set it.
  const totalFloor =
    scoreMode === "total_disaster" && game?.status === "LIVE"
      ? (game.away.score ?? 0) + (game.home.score ?? 0) + 0.5
      : null;
  useEffect(() => {
    if (totalFloor !== null && line < totalFloor) setLine(totalFloor);
  }, [totalFloor, line]);

  /** Why the sports part can't be sent yet, or null. */
  const problem = !sportsMode
    ? null
    : !game
      ? "Pick a game."
      : playerMode
        ? playerProblem(
            playerMode,
            picked.map((p) => ({ stat: statKey ? (p.stats[statKey] ?? 0) : 0 })),
            line,
          )
        : null;

  function pickGame(next: TickerGame) {
    setGameId(next.id);
    // A new league gets that league's default line; the same league keeps yours.
    if (scoreMode && (!game || game.league !== next.league))
      setLine(DEFAULT_LINE[next.league][scoreMode]);
    // Players and stats belong to a game, so a new game starts the player setup over.
    if (playerMode && next.id !== gameId) {
      setPlayerIds([]);
      setStat(null);
    }
  }

  /** A new league selects its first open game (or none), which starts the game's setup over like any new game. */
  function pickLeague(next: TickerGame["league"]) {
    setLeague(next);
    if (game?.league === next) return;
    const first = games.find((g) => g.league === next);
    if (first) pickGame(first);
    else {
      setGameId(null);
      setPlayerIds([]);
      setStat(null);
    }
  }

  /** A newly chosen mode: a sports mode starts on the first open game in your league (or the NFL). */
  function startMode(next: ModeDef) {
    setPlayerIds([]);
    setStat(null);
    if (!isSportsMode(next.id)) return;
    const startLeague = LEAGUES.includes(league) ? league : "NFL";
    const first = games.find((g) => g.league === startLeague) ?? games[0];
    setGameId(first?.id ?? null);
    setLeague(first?.league ?? startLeague);
    if (isScoreMode(next.id)) setLine(DEFAULT_LINE[first?.league ?? startLeague][next.id]);
  }

  /**
   * New players or a new stat: start the line or target at their season average (before kickoff, when everyone is on
   * 0), and never at or below what they've already done.
   */
  function choosePlayers(ids: string[], nextStat = statKey) {
    setPlayerIds(ids);
    if (!playerMode || playerMode === "either_or" || !nextStat || ids.length === 0) return;
    const chosen = ids.flatMap((id) => roster.data?.players.find((p) => p.id === id) ?? []);
    setLine(
      suggestedLine(
        playerMode,
        chosen.map((p) => ({ current: p.stats[nextStat] ?? 0, average: p.averages?.[nextStat] })),
      ),
    );
  }

  function chooseStat(next: string) {
    setStat(next);
    choosePlayers(playerIds, next);
  }

  function reset() {
    setGameId(null);
    setOvertime(true);
    setStat(null);
    setPlayerIds([]);
  }

  /** The preview ticket's sports terms (question, choices, config), or null when there's no game yet. */
  function previewTerms() {
    if (!sportsMode || !game) return null;
    if (scoreMode)
      return {
        modeId: scoreMode,
        config: { line },
        gameId: game.id,
        includesOvertime: overtime,
        ...scoreTerms(scoreMode, line, game.home.abbreviation, game.away.abbreviation),
      };
    return {
      modeId: sportsMode,
      config: {},
      gameId: game.id,
      ...(sportsMode === "scorcerer" ? {} : { includesOvertime: overtime }),
      ...playerTerms(
        sportsMode as PlayerMode | "scorcerer",
        statLabel,
        picked,
        line,
        game.home.abbreviation,
        game.away.abbreviation,
        game.league,
      ),
    };
  }

  /** The proposal to send (wager and window from the sheet), or null when there's no game. */
  function proposal(wagerCents: number, durationSec: number, forFun: boolean) {
    if (!sportsMode || !game) return null;
    return {
      modeId: sportsMode,
      gameId: game.id,
      line:
        sportsMode === "either_or" || sportsMode === "scorcerer"
          ? null
          : sportsMode === "king_of_the_hill"
            ? Math.round(line)
            : line,
      stat: playerMode ? statKey : null,
      players: playerMode ? playerIds : [],
      includesOvertime: overtime,
      wagerCents,
      durationSec,
      forFun,
    };
  }

  return {
    leagues: LEAGUES,
    league,
    gameId,
    game,
    games,
    lostGame,
    ticker,
    roster,
    sportsMode,
    scoreMode,
    playerMode,
    line,
    setLine,
    totalFloor,
    overtime,
    setOvertime,
    statKey,
    statLabel,
    playerIds,
    picked,
    problem,
    pickGame,
    pickLeague,
    startMode,
    choosePlayers,
    chooseStat,
    reset,
    previewTerms,
    proposal,
  };
}

export type SportsDraft = ReturnType<typeof useSportsDraft>;

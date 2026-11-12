import type { GameSnapshot } from "./live-stats";
import { playerName } from "./modes";
import { overtimeCounts } from "./overtime";
import type { Bet, BetBaseline, Game } from "./types";

export interface InfoRow {
  label: string;
  value: string;
}

const NONE = "—";
const statKey = (playerId: string, stat: string) => `${playerId}|${stat}`;
const str = (v: unknown) => (typeof v === "string" ? v : "");

/** Player/stat pairs a mode tracks from the moment it's sent. */
function trackedStats(bet: Pick<Bet, "modeId" | "config">): [string, string][] {
  const c = bet.config;
  const stat = str(c["stat"]);
  if (bet.modeId === "either_or" || bet.modeId === "king_of_the_hill")
    return [
      [str(c["playerA"]), stat],
      [str(c["playerB"]), stat],
    ];
  if (bet.modeId === "prop_hunt") return [[str(c["player"]), stat]];
  return [];
}

/** Starting values copied onto a bet when it's created. */
export function makeBaseline(
  bet: Pick<Bet, "modeId" | "config">,
  snap: GameSnapshot,
  at: string,
): BetBaseline {
  const stats: Record<string, number> = {};
  for (const [p, s] of trackedStats(bet)) stats[statKey(p, s)] = snap.stat(p, s);
  return {
    at,
    awayScore: snap.awayScore,
    homeScore: snap.homeScore,
    possession: snap.possession,
    yardLine: snap.yardLine,
    stats,
  };
}

const num = (n: number | undefined) => (n === undefined ? NONE : String(n));
const signed = (n: number | undefined) => (n === undefined ? NONE : n > 0 ? `+${n}` : String(n));

/**
 * The Info list for tickets: starting values, live values (from `now`) and the result.
 * Values not known yet show "—". The backend tracks more than this to resolve a bet.
 */
export function betInfo(
  bet: Bet,
  game: Game | undefined,
  now: GameSnapshot | undefined,
  nameOf: (userId: string) => string = (id) => id,
  settlerLabel = "",
): InfoRow[] {
  const rows: InfoRow[] = [];
  const b = bet.baseline;
  const c = bet.config;
  const add = (label: string, value: string) => rows.push({ label, value });
  const away = game?.away.abbr ?? "Away";
  const home = game?.home.abbr ?? "Home";

  const statRows = (withNet: boolean) => {
    for (const [p, s] of trackedStats(bet)) {
      const who = game ? playerName(game, p) : p;
      const start = b?.stats?.[statKey(p, s)];
      const cur = now?.stat(p, s);
      add(`${who} initial ${s}`, num(start));
      add(`${who} current ${s}`, num(cur));
      if (withNet)
        add(`${who} net ${s}`, signed(start !== undefined && cur !== undefined ? cur - start : undefined));
    }
  };
  const scores = () => {
    add(`${away} score`, num(now?.awayScore));
    add(`${home} score`, num(now?.homeScore));
  };

  switch (bet.modeId) {
    case "either_or":
      statRows(true);
      break;
    case "king_of_the_hill": {
      statRows(true);
      add("Target", String(c["target"] ?? NONE));
      const reached =
        bet.status === "resolved" && bet.winningChoice !== "Neither" ? bet.winningChoice : undefined;
      add("Reached it first", reached ?? NONE);
      break;
    }
    case "choose_their_fate": {
      const team = c["team"] === "home" ? home : away;
      add("Possessing team", team);
      add(game?.league === "nba" ? "Started at" : "Yard line", b?.yardLine ?? (game?.league === "nba" ? "Possession start" : NONE));
      break;
    }
    case "scorcerer":
      scores();
      break;
    case "total_disaster":
      scores();
      add("Combined total", now ? String(now.awayScore + now.homeScore) : NONE);
      add("Line", String(c["line"] ?? NONE));
      break;
    case "give_and_take": {
      const spread = Number(c["spread"] ?? 0);
      scores();
      add(`${home} score adjusted`, now ? String(now.homeScore + spread) : NONE);
      add("Spread", signed(spread));
      break;
    }
    case "prop_hunt":
      statRows(true);
      add("Line", String(c["line"] ?? NONE));
      break;
    case "u2pick":
      add("Settled by", settlerLabel || NONE);
      add("Result called by", bet.calledBy ? nameOf(bet.calledBy) : NONE);
      add("Called at", bet.calledBy ? (bet.closedAt ?? NONE) : NONE);
      break;
  }

  const ot = overtimeCounts(bet);
  if (ot !== undefined) add("Includes overtime?", ot ? "Yes" : "No");
  add("Winning choice", bet.status === "resolved" ? (bet.winningChoice ?? NONE) : NONE);
  add("Locked at", bet.lockedAt ?? NONE);
  add("Resolved at", bet.status === "resolved" || bet.status === "washed" ? (bet.closedAt ?? NONE) : NONE);
  return rows;
}

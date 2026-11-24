import type { TickerGame } from "@/features/sports";

/**
 * Score modes on the client: defaults, line steps and the question the server will build (SportsProposalService),
 * for the proposal sheet's preview. The server stays authoritative.
 */
export type ScoreMode = "total_disaster" | "give_and_take";

const SCORE_MODES: readonly ScoreMode[] = ["total_disaster", "give_and_take"];

export const isScoreMode = (mode: string): mode is ScoreMode =>
  (SCORE_MODES as readonly string[]).includes(mode);

/** Sensible starting lines per league. */
export const DEFAULT_LINE: Record<TickerGame["league"], Record<ScoreMode, number>> = {
  NBA: { total_disaster: 224.5, give_and_take: -5.5 },
  NFL: { total_disaster: 45.5, give_and_take: -3.5 },
};

export const LINE_LIMITS: Record<ScoreMode, { min: number; max: number }> = {
  total_disaster: { min: 0.5, max: 999.5 },
  give_and_take: { min: -99.5, max: 99.5 },
};

/** Keeps a line on a .5 (so it can never push), within `min` and `max`, which also end in .5. */
export function halfLine(line: number, min: number, max: number) {
  return Math.min(max, Math.max(min, Math.floor(line) + 0.5));
}

/** Moves a score mode's line by a step, kept on a .5 and within the mode's limits. */
export function stepLine(mode: ScoreMode, line: number, step: number) {
  const { min, max } = LINE_LIMITS[mode];
  return halfLine(line + step, min, max);
}

const signed = (n: number) => (n > 0 ? `+${n}` : `${n}`);

/** The question and choices the server builds; the first choice is "over" or the home side. */
export function scoreTerms(mode: ScoreMode, line: number, home: string, away: string) {
  return mode === "total_disaster"
    ? {
        question: `${away} + ${home} combined: over or under ${line}?`,
        choices: [`Over ${line}`, `Under ${line}`],
      }
    : {
        question: `${home} ${signed(line)}, who covers?`,
        choices: [`${home} ${signed(line)}`, `${away} ${signed(-line || 0)}`],
      };
}

/** Games that can take new bets: not yet Final, canceled or postponed. Live games first, then by start time. */
export function proposableGames(games: TickerGame[] | null | undefined) {
  return (games ?? [])
    .filter((game) => game.status === "SCHEDULED" || game.status === "LIVE")
    .sort(
      (a, b) =>
        Number(b.status === "LIVE") - Number(a.status === "LIVE") ||
        a.startsAt.localeCompare(b.startsAt),
    );
}

/** "NYK 98 @ BOS 101 · Q4 1:22" style line for a ticket stub. */
export function scoreLine(game: TickerGame, status: string) {
  const score = (s: number | null) => (s === null ? "" : ` ${s}`);
  return `${game.away.abbreviation}${score(game.away.score)} @ ${game.home.abbreviation}${score(game.home.score)} · ${status}`;
}

/** Player modes: both players from the bet's game (server: SportsProposalService). */
export type PlayerMode = "either_or" | "prop_hunt" | "king_of_the_hill";
const PLAYER_MODES: readonly PlayerMode[] = ["either_or", "prop_hunt", "king_of_the_hill"];
export const isPlayerMode = (mode: string): mode is PlayerMode =>
  (PLAYER_MODES as readonly string[]).includes(mode);

/**
 * Scorcerer: which team scores next, and how, away team first. NFL: touchdown or field goal (safeties and other
 * 2-point plays don't count). NBA: a 3 or a 2 (free throws don't count). Read from team totals of player stats; the
 * server builds the same choices.
 */
function scorcererChoices(league: "NBA" | "NFL", away: string, home: string) {
  const [big, small] = league === "NFL" ? ["touchdown", "field goal"] : ["3", "2"];
  return [
    `${away} ${big}`,
    `${away} ${small}`,
    `${home} ${big}`,
    `${home} ${small}`,
    "No more scores",
  ];
}

/** Every mode that binds to a game. */
export type SportsMode = ScoreMode | PlayerMode | "scorcerer";
export const isSportsMode = (mode: string): mode is SportsMode =>
  isScoreMode(mode) || isPlayerMode(mode) || mode === "scorcerer";

/** How many players a player mode takes. */
export const playerCount = (mode: PlayerMode) => (mode === "prop_hunt" ? 1 : 2);

type Named = { name: string; team: string | null };

/** Display names as choices; two players with the same name are told apart by team (as the server does). */
export function playerNames(players: Named[]) {
  const names = players.map((p) => p.name);
  return names.length === 2 && names[0]?.toLowerCase() === names[1]?.toLowerCase()
    ? players.map((p) => `${p.name} (${p.team ?? "?"})`)
    : names;
}

/** The question and choices the server builds for a player mode or Scorcerer. */
export function playerTerms(
  mode: PlayerMode | "scorcerer",
  statLabel: string,
  players: Named[],
  line: number,
  home: string,
  away: string,
  league: "NBA" | "NFL" = "NFL",
) {
  if (mode === "scorcerer")
    return {
      question: `${away} @ ${home}: ${league === "NFL" ? "next touchdown or field goal?" : "next basket?"}`,
      choices: scorcererChoices(league, away, home),
    };
  const stat = statLabel.toLowerCase();
  const names = playerNames(players);
  if (mode === "either_or")
    return { question: `Who gets more ${stat}: ${names[0]} or ${names[1]}?`, choices: names };
  if (mode === "prop_hunt")
    return {
      question: `${names[0]} ${stat}: over or under ${line}?`,
      choices: [`Over ${line}`, `Under ${line}`],
    };
  return {
    question: `First to ${line} ${stat}: ${names[0]} or ${names[1]}?`,
    choices: [names[0] ?? "", names[1] ?? "", "Neither"],
  };
}

/**
 * A line (Prop Hunt) or target (King Of The Hill) that starts just above the players' current numbers: the server
 * requires it to be above them (domain rules, "Wash triggers").
 */
export function startingLine(mode: PlayerMode, current: number[]) {
  const top = Math.max(0, ...current);
  return mode === "prop_hunt" ? top + 0.5 : top + 1;
}

/**
 * A starting line or target for the chosen players: their season average when there is one (Prop Hunt as a .5 line,
 * King Of The Hill the higher of the two), never below what they've already done.
 */
export function suggestedLine(
  mode: PlayerMode,
  players: { current: number; average: number | undefined }[],
) {
  const floor = startingLine(
    mode,
    players.map((p) => p.current),
  );
  const averages = players.flatMap((p) => (p.average === undefined ? [] : [p.average]));
  if (averages.length === 0) return floor;
  const top = Math.max(...averages);
  return Math.max(floor, mode === "prop_hunt" ? Math.floor(top) + 0.5 : Math.round(top));
}

/** Why a player-mode proposal can't be sent yet, or null. Mirrors the server's checks. */
export function playerProblem(mode: PlayerMode, players: { stat: number }[], line: number) {
  if (players.length < playerCount(mode))
    return playerCount(mode) === 1 ? "Pick a player." : "Pick two players.";
  if (mode === "prop_hunt" && players[0] && players[0].stat >= line)
    return "The line must be above the player's current number.";
  if (mode === "king_of_the_hill" && players.some((p) => p.stat >= line))
    return "The target must be above both players' current numbers.";
  return null;
}

import type { TickerGame } from "@/features/sports";

/**
 * Score modes on the client: defaults, line steps and the question the server will build (SportsProposalService),
 * for the proposal sheet's preview. The server stays authoritative.
 */
export type ScoreMode = "total_disaster" | "give_and_take";

export const SCORE_MODES: readonly ScoreMode[] = ["total_disaster", "give_and_take"];

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

/** Moves a line by a step, kept to half points and within the mode's limits. */
export function stepLine(mode: ScoreMode, line: number, step: number) {
  const { min, max } = LINE_LIMITS[mode];
  return Math.min(max, Math.max(min, Math.round((line + step) * 2) / 2));
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

/** Whole-number lines can push, which washes the bet. */
export const canPush = (line: number) => Number.isInteger(line);

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

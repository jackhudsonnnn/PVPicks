import type { Game } from "./types";

/**
 * What the sports feed knows about a game right now.
 * TODO(backend): the live feed replaces `sampleSnapshot`; screens only read `GameSnapshot`.
 */
export interface GameSnapshot {
  awayScore: number;
  homeScore: number;
  /** Team with the ball (NFL) / possession (NBA). */
  possession: "home" | "away";
  /** NFL only, e.g. "KC 35". */
  yardLine?: string | undefined;
  stat: (playerId: string, stat: string) => number;
}

function hash(s: string) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/** Deterministic sample stats that grow with the score, so "current" moves as the game does. */
export function sampleSnapshot(game: Game): GameSnapshot {
  const progress = game.homeScore + game.awayScore;
  const possession = progress % 2 === 0 ? "away" : "home";
  const team = possession === "home" ? game.home : game.away;
  return {
    awayScore: game.awayScore,
    homeScore: game.homeScore,
    possession,
    yardLine: game.league === "nfl" ? `${team.abbr} ${20 + (hash(game.id) + progress) % 30}` : undefined,
    stat: (playerId, stat) => {
      const rate = (hash(`${game.id}|${playerId}|${stat}`) % 7) + 1;
      return Math.round((progress * rate) / (game.league === "nba" ? 6 : 1));
    },
  };
}

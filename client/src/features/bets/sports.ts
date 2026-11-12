import type { Game, League } from "./types";

/**
 * Per-league data the game modes read: stat lists, outcome sets and sensible default lines.
 * Modes keep the same names across leagues; only this data changes.
 */
export interface SportDef {
  league: League;
  label: string;
  stats: readonly string[];
  /** What one offensive turn is called ("drive" / "possession"). */
  turn: string;
  turnOutcomes: string[];
  nextScoreQuestion: string;
  nextScoreOutcomes: string[];
  defaults: {
    eitherStat: string;
    raceStat: string;
    raceTarget: number;
    totalLine: number;
    spread: number;
    propStat: string;
    propLine: number;
  };
}

export const SPORTS: Record<League, SportDef> = {
  nfl: {
    league: "nfl",
    label: "NFL",
    stats: ["Passing Yards", "Rushing Yards", "Receiving Yards", "Receptions", "Touchdowns"],
    turn: "drive",
    turnOutcomes: ["Touchdown", "Field Goal", "Punt", "Turnover"],
    nextScoreQuestion: "How will the next score happen?",
    nextScoreOutcomes: ["Touchdown", "Field Goal", "Safety", "No More Scores"],
    defaults: {
      eitherStat: "Passing Yards",
      raceStat: "Rushing Yards",
      raceTarget: 75,
      totalLine: 45.5,
      spread: -3.5,
      propStat: "Receiving Yards",
      propLine: 64.5,
    },
  },
  nba: {
    league: "nba",
    label: "NBA",
    stats: ["Points", "Rebounds", "Assists", "3-Pointers Made", "Steals + Blocks"],
    turn: "possession",
    turnOutcomes: ["Three-Pointer", "Two-Pointer", "Free Throws", "Empty Trip"],
    nextScoreQuestion: "How will the next basket be scored?",
    nextScoreOutcomes: ["Three-Pointer", "Dunk or Layup", "Jump Shot", "Free Throw"],
    defaults: {
      eitherStat: "Points",
      raceStat: "Points",
      raceTarget: 20,
      totalLine: 224.5,
      spread: -5.5,
      propStat: "Points",
      propLine: 24.5,
    },
  },
};

export const sportOf = (game: Game) => SPORTS[game.league];
export const LEAGUES = Object.keys(SPORTS) as League[];

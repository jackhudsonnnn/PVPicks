import {
  Crown,
  Flame,
  PenLine,
  Scale,
  Split,
  Swords,
  Target,
  Wand2,
  type LucideIcon,
} from "lucide-react";
import { sportOf } from "./sports";
import type { BetConfig, Game, ModeId, Player } from "./types";

export type FieldDef =
  | { key: string; label: string; type: "player" }
  | { key: string; label: string; type: "stat" }
  | { key: string; label: string; type: "team" }
  | { key: string; label: string; type: "number"; step: number; min?: number; suffix?: string };

export interface ModeDef {
  id: ModeId;
  name: string;
  tagline: string;
  description: string;
  icon: LucideIcon;
  /**
   * Custom mode: no game; the proposer writes the question (`config.question`), the options
   * (`config.options`) and picks who settles it. See `u2pick.ts`.
   */
  custom?: boolean;
  fields: FieldDef[];
  defaults: (game: Game) => BetConfig;
  question: (c: BetConfig, game: Game) => string;
  choices: (c: BetConfig, game: Game) => string[];
}

export function allPlayers(game: Game): Player[] {
  return [...game.away.players, ...game.home.players];
}

export function playerName(game: Game, id: unknown) {
  return allPlayers(game).find((p) => p.id === id)?.name ?? "Player";
}

/** Stats a "stat" field offers for this game's league. */
export const statsFor = (game: Game) => sportOf(game).stats;

function teamLabel(game: Game, side: unknown) {
  return side === "home" ? game.home.abbr : game.away.abbr;
}

export const MODES: ModeDef[] = [
  {
    id: "either_or",
    name: "Either Or",
    tagline: "Who racks up more?",
    description:
      "Pick which of two players will accumulate more of a stat, like passing yards or points, by the deadline.",
    icon: Swords,
    fields: [
      { key: "playerA", label: "Player A", type: "player" },
      { key: "playerB", label: "Player B", type: "player" },
      { key: "stat", label: "Stat", type: "stat" },
    ],
    defaults: (g) => ({
      playerA: g.away.players[0]!.id,
      playerB: g.home.players[0]!.id,
      stat: sportOf(g).defaults.eitherStat,
    }),
    question: (c, g) =>
      `More ${String(c["stat"]).toLowerCase()}: ${playerName(g, c["playerA"])} or ${playerName(g, c["playerB"])}?`,
    choices: (c, g) => [playerName(g, c["playerA"]), playerName(g, c["playerB"])],
  },
  {
    id: "king_of_the_hill",
    name: "King Of The Hill",
    tagline: "First to the milestone",
    description:
      "A race: which of two players reaches the target first? If the game ends before either does, Neither wins.",
    icon: Crown,
    fields: [
      { key: "playerA", label: "Player A", type: "player" },
      { key: "playerB", label: "Player B", type: "player" },
      { key: "stat", label: "Stat", type: "stat" },
      { key: "target", label: "Target", type: "number", step: 5, min: 1 },
    ],
    defaults: (g) => ({
      playerA: g.away.players[1]!.id,
      playerB: g.home.players[1]!.id,
      stat: sportOf(g).defaults.raceStat,
      target: sportOf(g).defaults.raceTarget,
    }),
    question: (c, g) =>
      `First to ${c["target"]} ${String(c["stat"]).toLowerCase()}: ${playerName(g, c["playerA"])} or ${playerName(g, c["playerB"])}?`,
    choices: (c, g) => [playerName(g, c["playerA"]), playerName(g, c["playerB"]), "Neither"],
  },
  {
    id: "choose_their_fate",
    name: "Choose Their Fate",
    tagline: "How does this one end?",
    description:
      "Predict how the current drive (NFL) or possession (NBA) ends, from touchdowns to empty trips.",
    icon: Wand2,
    fields: [{ key: "team", label: "Team on offense", type: "team" }],
    defaults: () => ({ team: "away" }),
    question: (c, g) => `How does this ${teamLabel(g, c["team"])} ${sportOf(g).turn} end?`,
    choices: (_c, g) => [...sportOf(g).turnOutcomes],
  },
  {
    id: "scorcerer",
    name: "Scorcerer",
    tagline: "Call the next score",
    description:
      "Predict the method of the next scoring play. Keeps things tense between big moments.",
    icon: Flame,
    fields: [],
    defaults: () => ({}),
    question: (_c, g) => sportOf(g).nextScoreQuestion,
    choices: (_c, g) => [...sportOf(g).nextScoreOutcomes],
  },
  {
    id: "total_disaster",
    name: "Total Disaster",
    tagline: "Over / Under the total",
    description:
      "The proposer sets a line for the combined final score. Will the game finish over or under it?",
    icon: Target,
    fields: [{ key: "line", label: "Total points line", type: "number", step: 0.5, min: 0 }],
    defaults: (g) => ({ line: sportOf(g).defaults.totalLine }),
    question: (c, g) => `${g.away.abbr} + ${g.home.abbr} combined: over or under ${c["line"]}?`,
    choices: (c) => [`Over ${c["line"]}`, `Under ${c["line"]}`],
  },
  {
    id: "give_and_take",
    name: "Give And Take",
    tagline: "Who covers the spread?",
    description:
      "A point handicap is applied to the home team. Pick which side covers, margin matters, not just the winner.",
    icon: Scale,
    fields: [{ key: "spread", label: "Home spread", type: "number", step: 0.5 }],
    defaults: (g) => ({ spread: sportOf(g).defaults.spread }),
    question: (c, g) => `${g.home.abbr} ${fmtSpread(Number(c["spread"]))}, who covers?`,
    choices: (c, g) => [
      `${g.home.abbr} ${fmtSpread(Number(c["spread"]))}`,
      `${g.away.abbr} ${fmtSpread(-Number(c["spread"]))}`,
    ],
  },
  {
    id: "prop_hunt",
    name: "Prop Hunt",
    tagline: "One player, one line",
    description:
      "Will a single athlete finish over or under a stat line set by the proposer? Classic player prop, peer-to-peer.",
    icon: Split,
    fields: [
      { key: "player", label: "Player", type: "player" },
      { key: "stat", label: "Stat", type: "stat" },
      { key: "line", label: "Line", type: "number", step: 0.5, min: 0 },
    ],
    defaults: (g) => ({
      player: g.home.players[2]!.id,
      stat: sportOf(g).defaults.propStat,
      line: sportOf(g).defaults.propLine,
    }),
    question: (c, g) =>
      `${playerName(g, c["player"])}: over or under ${c["line"]} ${String(c["stat"]).toLowerCase()}?`,
    choices: (c) => [`Over ${c["line"]}`, `Under ${c["line"]}`],
  },
  {
    id: "u2pick",
    name: "U2Pick",
    tagline: "Your bet, your options",
    description:
      "Bet on anything. Write the question and the options, then choose who gets to call the result: anyone, people not in it, hosts, or just you.",
    icon: PenLine,
    custom: true,
    fields: [],
    defaults: () => ({ question: "", options: ["Yes", "No"] }),
    question: (c) => String(c["question"] ?? "").trim(),
    choices: (c) => (Array.isArray(c["options"]) ? c["options"] : []),
  },
];

/**
 * Setup mistakes that would make a bet unfair or impossible to settle.
 * Ties (Either Or), pushes on whole-number lines and outcomes not listed wash under the payout rules.
 */
export function modeProblem(modeId: ModeId, c: BetConfig): string | null {
  if (
    (modeId === "either_or" || modeId === "king_of_the_hill") &&
    c["playerA"] !== undefined &&
    c["playerA"] === c["playerB"]
  )
    return "Pick two different players.";
  if (modeId === "king_of_the_hill" && !(Number(c["target"]) > 0))
    return "The target has to be above 0.";
  return null;
}

export function getMode(id: ModeId) {
  return MODES.find((m) => m.id === id)!;
}

function fmtSpread(n: number) {
  return n > 0 ? `+${n}` : `${n}`;
}

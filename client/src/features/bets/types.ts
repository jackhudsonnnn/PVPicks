import type { RemainderOptions } from "./payout";

export type BetStatus = "active" | "pending" | "washed" | "resolved";

export const PASS = "__no_guess__";

export type ModeId =
  | "either_or"
  | "king_of_the_hill"
  | "choose_their_fate"
  | "scorcerer"
  | "total_disaster"
  | "give_and_take"
  | "prop_hunt"
  | "u2pick";

export type BetConfig = Record<string, string | number | string[]>;

/** Who may call the outcome of a U2Pick (custom) bet. */
export type SettlerRule = "anyone" | "non_participants" | "hosts" | "me";

/** Game values captured when a bet is sent; current values come from the live feed. */
export interface BetBaseline {
  at: string;
  awayScore: number;
  homeScore: number;
  possession?: "home" | "away" | undefined;
  yardLine?: string | undefined;
  /** Keyed `${playerId}|${stat}`. */
  stats?: Record<string, number> | undefined;
}

export interface Participant {
  userId: string;
  /** Selected choice, or PASS after you clear your guess (you stay in the bet, out of the pot). */
  selection: string;
}

export interface Bet {
  id: string;
  tableId: string;
  proposerId: string;
  modeId: ModeId;
  /** Missing for custom (U2Pick) bets that aren't tied to a game. */
  gameId?: string | undefined;
  config: BetConfig;
  question: string;
  choices: string[];
  wager: number;
  durationSec: number;
  closesInSec: number;
  status: BetStatus;
  participants: Participant[];
  /** 4fun: no points at stake and nothing tracked (no pot, record, settle-ups). */
  forFun?: boolean | undefined;
  /** Whether overtime counts (see `overtime.ts`). Missing = default for the mode. */
  overtime?: boolean | undefined;
  /** Game values when the bet was sent (see `info.ts`). */
  baseline?: BetBaseline | undefined;
  /** U2Pick: who called the result. */
  calledBy?: string | undefined;
  /** U2Pick only: who can settle it. Game bets settle from the game result. */
  settledBy?: SettlerRule | undefined;
  winningChoice?: string | undefined;
  washReason?: string | undefined;
  deltas?: Record<string, number> | undefined;
  /** Paid out in a host "Settle points"; no longer counts toward balances or nets. */
  settled?: boolean | undefined;
  createdAt: string;
  /** When picks locked (Active → Pending). */
  lockedAt?: string | undefined;
  /** Leftover-cent rule, fixed from the table's setting the moment picks lock. */
  remainder?: RemainderOptions | undefined;
  /** When it resolved or washed. */
  closedAt?: string | undefined;
  /** When the host settled points for it. */
  settledAt?: string | undefined;
  /** Set on Quick Pick copies: where it came from. The result follows the source unless overridden. */
  quickPick?:
    | { sourceTableId: string; sourceBetId: string; sourceName: string; overridden?: boolean }
    | undefined;
}

export interface Player {
  id: string;
  name: string;
  pos: string;
}

export interface Team {
  abbr: string;
  city: string;
  name: string;
  players: Player[];
}

export type League = "nfl" | "nba";

interface GameBase {
  id: string;
  home: Team;
  away: Team;
  homeScore: number;
  awayScore: number;
  clock: string;
  live: boolean;
}

/**
 * Games are split by league so each can grow its own live-feed fields
 * (e.g. NFL possession/down, NBA fouls/bonus) without touching the other.
 * League-specific stats and outcomes live in `sports.ts`.
 */
export interface NflGame extends GameBase {
  league: "nfl";
}

export interface NbaGame extends GameBase {
  league: "nba";
}

export type Game = NflGame | NbaGame;

import { z } from "zod";
import { idSchema, read, request } from "@/lib/api-client";

/**
 * Jackblack (domain rules, "Jackblack"): everyone who joins deals once, in a random order, and banks
 * that round against the rest. Players choose in lockstep: new cards come out only once everyone still playing has
 * chosen, or the decision timer runs out. The server owns the shoe and every rule; this module mirrors the rule
 * limits for the setup form and reads the game.
 */

export interface JackblackRules {
  doubling: boolean;
  maxSplits: number;
  blackjackPays: "3:2" | "6:5";
  decisionSec: number;
  /** Fewer at the deal washes; joins stop at the most (the dealer counts). */
  minPlayers: number;
  maxPlayers: number;
}

/** Each number's minimum and maximum (the server enforces the same). */
export const RULE_LIMITS = {
  maxSplits: { min: 0, max: 3 },
  decisionSec: { min: 10, max: 60 },
} as const;

/** Players at one game, the dealer included. */
export const PLAYER_LIMITS = { min: 2, max: 7 } as const;

export const DEFAULT_RULES: JackblackRules = {
  doubling: true,
  maxSplits: 1,
  blackjackPays: "3:2",
  decisionSec: 30,
  minPlayers: 2,
  maxPlayers: 7,
};

/** What's wrong with the rules, or null. */
export function rulesProblem(rules: JackblackRules): string | null {
  const within = (v: number, { min, max }: { min: number; max: number }) => v >= min && v <= max;
  if (!within(rules.maxSplits, RULE_LIMITS.maxSplits)) return "Allow 0 to 3 splits.";
  if (!within(rules.decisionSec, RULE_LIMITS.decisionSec))
    return "The decision timer is 10 to 60 seconds.";
  if (
    rules.minPlayers < PLAYER_LIMITS.min ||
    rules.maxPlayers > PLAYER_LIMITS.max ||
    rules.minPlayers > rules.maxPlayers
  )
    return "Players are 2 to 7.";
  return null;
}

/** The most one hand can put at risk: every split taken and every hand doubled. */
function handWorstCase(rules: JackblackRules, anteCents: number) {
  return anteCents * (rules.doubling ? 2 : 1) * (rules.maxSplits + 1);
}

/**
 * The most a player can lose over the whole game (the server holds it against their points limit): every round
 * they sit at the hand's worst case, and every round they deal paying everyone the most a seat can win.
 */
export function worstCase(rules: JackblackRules, anteCents: number, players: number) {
  const natural = Math.floor(
    (anteCents * (rules.blackjackPays === "3:2" ? 3 : 6)) / (rules.blackjackPays === "3:2" ? 2 : 5),
  );
  const dealing = Math.max(handWorstCase(rules, anteCents), natural);
  return (handWorstCase(rules, anteCents) + dealing) * (players - 1);
}

/** Reads rules back from a bet's config (booleans are stored as "yes"/"no"). */
export function rulesFromConfig(config: Record<string, unknown>): JackblackRules {
  const num = (key: "maxSplits" | "decisionSec" | "minPlayers" | "maxPlayers") =>
    Number(config[key] ?? DEFAULT_RULES[key]);
  return {
    doubling: config["doubling"] !== "no",
    maxSplits: num("maxSplits"),
    blackjackPays: config["blackjackPays"] === "6:5" ? "6:5" : "3:2",
    decisionSec: num("decisionSec"),
    minPlayers: num("minPlayers"),
    maxPlayers: num("maxPlayers"),
  };
}

const card = z.string().regex(/^([A2-9TJQK][SHDC]|\?\?)$/);
const handSchema = z.object({
  cards: z.array(card),
  total: z.number().int(),
  soft: z.boolean(),
  stakeCents: z.number().int(),
  doubled: z.boolean(),
  done: z.boolean(),
  /** The hand this step's choice is for (only in a live round). */
  current: z.boolean(),
  result: z.enum(["win", "blackjack", "push", "lose", "bust"]).optional(),
  deltaCents: z.number().int().optional(),
});
const choice = z.enum(["hit", "stand", "double", "split"]);
const seatSchema = z.object({
  userId: idSchema,
  /**
   * In a live round, only your own seat's hands, or every seat's when you're dealing: nobody sees other players'
   * cards (domain rules, "Jackblack", "Who sees what"). Finished rounds carry every seat's.
   */
  hands: z.array(handSchema).optional(),
  /** During a step: whether this player still has to choose (never what anyone chose). */
  deciding: z.boolean().optional(),
  /** Your own choice this step, before the cards come out. */
  choice: choice.optional(),
  canDouble: z.boolean().optional(),
  canSplit: z.boolean().optional(),
  /** In a finished round. */
  deltaCents: z.number().int().optional(),
});
const roundSchema = z.object({
  number: z.number().int(),
  dealerId: idSchema,
  dealer: z.array(card),
  dealerTotal: z.number().int(),
  dealerDeltaCents: z.number().int(),
  seats: z.array(seatSchema),
});
const gameSchema = z.object({
  /** "results": the pause after a round, showing how it went (its results are the last of `history`). */
  phase: z.enum(["waiting", "play", "dealer", "results", "done"]),
  /** While waiting: who has joined, in joining order. */
  players: z.array(idSchema).optional(),
  round: z.number().int().optional(),
  rounds: z.number().int().optional(),
  /** The dealing order. */
  order: z.array(idSchema).optional(),
  dealerId: idSchema.optional(),
  dealer: z.array(card).optional(),
  dealerTotal: z.number().int().optional(),
  dealerMove: z.enum(["flip", "hit", "stand"]).optional(),
  deadline: z.string().datetime({ offset: true }).optional(),
  seats: z.array(seatSchema).optional(),
  totals: z.record(idSchema, z.number().int()).optional(),
  history: z.array(roundSchema).optional(),
  /** Each round's shoe commitment from the deal; its deck count and seed once the game is over. */
  shuffles: z
    .array(
      z.object({
        round: z.number().int().min(1),
        commitment: z.string().regex(/^[0-9a-f]{64}$/),
        decks: z.number().int().min(1).max(8).optional(),
        seed: z
          .string()
          .regex(/^[0-9a-f]{64}$/)
          .optional(),
      }),
    )
    .optional(),
});

export type JackblackGame = z.infer<typeof gameSchema>;
export type JackblackSeat = z.infer<typeof seatSchema>;
export type JackblackHand = z.infer<typeof handSchema>;
export type JackblackChoice = z.infer<typeof choice>;

export async function getJackblackGame(betId: string, signal?: AbortSignal) {
  return read(
    await request(`/bets/${encodeURIComponent(betId)}/jackblack`, signal ? { signal } : undefined),
    gameSchema,
  );
}

/** Everyone in the game: the joined players while waiting, then the dealing order. */
export function playersOf(game: JackblackGame) {
  return game.order ?? game.players ?? [];
}

/** The viewer's seat this round, when they're sitting (not dealing). */
export function mySeat(game: JackblackGame, userId: string) {
  return game.seats?.find((s) => s.userId === userId);
}

/** Whether the viewer still has a choice to make this step. */
export function deciding(game: JackblackGame, userId: string) {
  return game.phase === "play" && mySeat(game, userId)?.deciding === true;
}

/** How many seats still have to choose this step. */
export function undecided(game: JackblackGame) {
  return game.seats?.filter((s) => s.deciding).length ?? 0;
}

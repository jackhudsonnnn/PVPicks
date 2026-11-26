import { z } from "zod";
import { idSchema, read, request } from "@/lib/api-client";

/**
 * Hold'em (domain rules, "Hold'em"): no-limit Texas hold'em over one full cycle, a hand each with the button moving
 * round. The wager is the big blind (the small blind is half), and every hand starts everyone on the same stack of 10
 * big blinds, so nobody busts out (owner decision, 2026-11-26). The server owns the deck and every rule; this module
 * mirrors the limits for the
 * setup form and reads the game.
 */

export interface HoldemRules {
  decisionSec: number;
  /** Fewer at the deal washes; joins stop at the most. */
  minPlayers: number;
  maxPlayers: number;
}

export const HOLDEM_LIMITS = {
  decisionSec: { min: 10, max: 60 },
  players: { min: 2, max: 9 },
} as const;

export const DEFAULT_HOLDEM_RULES: HoldemRules = {
  decisionSec: 30,
  minPlayers: 2,
  maxPlayers: 9,
};

/** Everyone's stack at the start of every hand, in big blinds. */
export const STACK_BIG_BLINDS = 10;

export function holdemRulesProblem(rules: HoldemRules): string | null {
  const within = (v: number, { min, max }: { min: number; max: number }) => v >= min && v <= max;
  if (!within(rules.decisionSec, HOLDEM_LIMITS.decisionSec))
    return "The decision timer is 10 to 60 seconds.";
  const { min, max } = HOLDEM_LIMITS.players;
  if (rules.minPlayers < min || rules.maxPlayers > max || rules.minPlayers > rules.maxPlayers)
    return "Players are 2 to 9.";
  return null;
}

/** The most a player can lose: their whole stack in every hand, one hand per player. */
export const holdemWorstCase = (bigBlindCents: number, players: number) =>
  bigBlindCents * STACK_BIG_BLINDS * players;

export function holdemRulesFromConfig(config: Record<string, unknown>): HoldemRules {
  return {
    decisionSec: Number(config["decisionSec"] ?? DEFAULT_HOLDEM_RULES.decisionSec),
    minPlayers: Number(config["minPlayers"] ?? DEFAULT_HOLDEM_RULES.minPlayers),
    maxPlayers: Number(config["maxPlayers"] ?? DEFAULT_HOLDEM_RULES.maxPlayers),
  };
}

const card = z.string().regex(/^[A2-9TJQK][SHDC]$/);
const cents = z.number().int();
const byUser = <T extends z.ZodTypeAny>(value: T) => z.record(idSchema, value);

const handSchema = z.object({
  number: z.number().int(),
  buttonId: idSchema,
  smallBlindId: idSchema,
  bigBlindId: idSchema,
  board: z.array(card),
  showdown: z.boolean(),
  pots: z.array(
    z.object({ amount: cents, winners: z.array(idSchema), handName: z.string().optional() }),
  ),
  /** Hole cards shown at a showdown: everyone still in (no mucking; the audit reveals every hand anyway). */
  shown: byUser(z.array(card)),
  handNames: byUser(z.string()),
  atShowdown: z.array(idSchema),
  deltas: byUser(cents),
  /** Everyone's hole cards, only once the game is over (the audit). */
  holes: byUser(z.array(card)).optional(),
});

const gameSchema = z.object({
  phase: z.enum(["waiting", "play", "results", "done"]),
  players: z.array(idSchema).optional(),
  hand: z.number().int().optional(),
  hands: z.number().int().optional(),
  /** Seating order; the button started on the first seat. */
  order: z.array(idSchema).optional(),
  bigBlindCents: cents.optional(),
  smallBlindCents: cents.optional(),
  /** Everyone's stack at the start of every hand. */
  stackCents: cents.optional(),
  /** This hand's stacks. */
  stacks: byUser(cents).optional(),
  deadline: z.string().datetime({ offset: true }).optional(),
  buttonId: idSchema.optional(),
  smallBlindId: idSchema.optional(),
  bigBlindId: idSchema.optional(),
  street: z.enum(["preflop", "flop", "turn", "river"]).optional(),
  board: z.array(card).optional(),
  pot: cents.optional(),
  currentBet: cents.optional(),
  toActId: idSchema.optional(),
  seats: z
    .array(
      z.object({
        userId: idSchema,
        committed: cents,
        contributed: cents,
        folded: z.boolean(),
        allIn: z.boolean(),
      }),
    )
    .optional(),
  /** Your own hole cards, and on your turn what you may do. */
  me: z
    .object({
      hole: z.array(card),
      /** Your best hand with the board so far, once the flop is out. */
      handName: z.string().optional(),
      /** The pre-move you've armed; the server plays it when your turn arrives. */
      preMove: z.enum(["check_fold", "call_any"]).optional(),
      toCall: cents.optional(),
      canRaise: z.boolean().optional(),
      minRaiseTo: cents.optional(),
      maxRaiseTo: cents.optional(),
    })
    .optional(),
  /** Each player's result so far, over the finished hands. */
  totals: byUser(cents).optional(),
  history: z.array(handSchema).optional(),
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

export type HoldemGame = z.infer<typeof gameSchema>;
export type HoldemMove =
  "fold" | "check" | "call" | "raise" | "all_in" | "check_fold" | "call_any" | "no_pre_move";
export type HoldemPreMove = NonNullable<NonNullable<HoldemGame["me"]>["preMove"]>;

export async function getHoldemGame(betId: string, signal?: AbortSignal) {
  return read(
    await request(`/bets/${encodeURIComponent(betId)}/holdem`, signal ? { signal } : undefined),
    gameSchema,
  );
}

/** Everyone in the game: the joined players while waiting, then the seating order. */
export function holdemPlayers(game: HoldemGame) {
  return game.order ?? game.players ?? [];
}

/** "BTN", "SB" or "BB" for whoever holds a position this hand. */
export function positionOf(game: HoldemGame, userId: string): string | null {
  if (userId === game.buttonId) return game.buttonId === game.smallBlindId ? "BTN · SB" : "BTN";
  if (userId === game.smallBlindId) return "SB";
  if (userId === game.bigBlindId) return "BB";
  return null;
}

/** How the board reads: "Pre-flop", "Flop", "Turn" or "River". */
export const STREET_LABEL = {
  preflop: "Pre-flop",
  flop: "Flop",
  turn: "Turn",
  river: "River",
} as const;

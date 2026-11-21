import { z } from "zod";
import { idSchema, read, request } from "@/lib/api-client";

/**
 * Blackjack (domain rules, "Blackjack"): the proposer deals and banks one hand; players sit down with a bet while
 * the join window is open, then play against an automatic dealer. The server owns the shoe and every rule; this
 * module mirrors the rule limits for the setup form and reads the hand.
 */

export interface BlackjackRules {
  decks: number;
  minBetCents: number;
  maxBetCents: number;
  maxSplits: number;
  dealerHitsSoft17: boolean;
  blackjackPays: "3:2" | "6:5";
  seats: number;
  doubleOn: "any" | "9-11";
  doubleAfterSplit: boolean;
  insurance: boolean;
  turnOrder: "together" | "seats";
  joinSec: number;
  decisionSec: number;
}

/** Each number's minimum and maximum (the server enforces the same). Bets are in cents. */
export const RULE_LIMITS = {
  decks: { min: 1, max: 8 },
  betCents: { min: 10, max: 1000 },
  maxSplits: { min: 0, max: 5 },
  seats: { min: 1, max: 7 },
  joinSec: { min: 30, max: 600 },
  decisionSec: { min: 10, max: 120 },
} as const;

export const DEFAULT_RULES: BlackjackRules = {
  decks: 6,
  minBetCents: 100,
  maxBetCents: 1000,
  maxSplits: 3,
  dealerHitsSoft17: false,
  blackjackPays: "3:2",
  seats: 7,
  doubleOn: "any",
  doubleAfterSplit: true,
  insurance: false,
  turnOrder: "together",
  joinSec: 120,
  decisionSec: 30,
};

/** What's wrong with the rules, or null. */
export function rulesProblem(rules: BlackjackRules): string | null {
  const within = (v: number, { min, max }: { min: number; max: number }) => v >= min && v <= max;
  if (!within(rules.decks, RULE_LIMITS.decks)) return "Use 1 to 8 decks.";
  if (
    !within(rules.minBetCents, RULE_LIMITS.betCents) ||
    !within(rules.maxBetCents, RULE_LIMITS.betCents)
  )
    return "Bets go from 0.10 to 10 points.";
  if (rules.minBetCents > rules.maxBetCents) return "The minimum bet can't be above the maximum.";
  if (!within(rules.maxSplits, RULE_LIMITS.maxSplits)) return "Allow 0 to 5 splits.";
  if (!within(rules.seats, RULE_LIMITS.seats)) return "Use 1 to 7 seats.";
  if (!within(rules.joinSec, RULE_LIMITS.joinSec))
    return "The join window is 30 seconds to 10 minutes.";
  if (!within(rules.decisionSec, RULE_LIMITS.decisionSec))
    return "The decision timer is 10 seconds to 2 minutes.";
  return null;
}

/**
 * The most the dealer can lose: every seat at the maximum bet, every split taken, every hand doubled and won.
 * Must fit the dealer's points limit.
 */
export function dealerWorstCase(rules: BlackjackRules) {
  return rules.seats * rules.maxBetCents * 2 * (rules.maxSplits + 1);
}

/** Reads rules back from a bet's config (booleans are stored as "yes"/"no"). */
export function rulesFromConfig(config: Record<string, unknown>): BlackjackRules {
  const num = (key: keyof BlackjackRules) => Number(config[key] ?? DEFAULT_RULES[key]);
  const yes = (key: keyof BlackjackRules) => config[key] === "yes";
  return {
    decks: num("decks"),
    minBetCents: num("minBetCents"),
    maxBetCents: num("maxBetCents"),
    maxSplits: num("maxSplits"),
    dealerHitsSoft17: yes("dealerHitsSoft17"),
    blackjackPays: config["blackjackPays"] === "6:5" ? "6:5" : "3:2",
    seats: num("seats"),
    doubleOn: config["doubleOn"] === "9-11" ? "9-11" : "any",
    // Always on (owner decision, 2026-11-20); older hands may say "no".
    doubleAfterSplit: true,
    insurance: yes("insurance"),
    turnOrder: config["turnOrder"] === "seats" ? "seats" : "together",
    joinSec: num("joinSec"),
    decisionSec: num("decisionSec"),
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
  result: z.enum(["win", "blackjack", "push", "lose", "bust"]).optional(),
  deltaCents: z.number().int().optional(),
});
const seatSchema = z.object({
  userId: idSchema,
  seat: z.number().int().min(1).max(7),
  betCents: z.number().int(),
  insuranceCents: z.number().int().optional(),
  insuranceOpen: z.boolean().optional(),
  deadline: z.string().datetime({ offset: true }).optional(),
  deltaCents: z.number().int().optional(),
  hands: z.array(handSchema).optional(),
});
const blackjackHandSchema = z.object({
  phase: z.enum(["waiting", "insurance", "play", "dealer", "done"]),
  dealer: z.array(card),
  dealerTotal: z.number().int().optional(),
  /** On the dealer's turn: what their one button does next. */
  dealerMove: z.enum(["flip", "hit", "stand"]).optional(),
  dealerDeadline: z.string().datetime({ offset: true }).optional(),
  dealerDeltaCents: z.number().int().optional(),
  turnSeat: z.number().int().optional(),
  seats: z.array(seatSchema),
});

export type BlackjackHand = z.infer<typeof blackjackHandSchema>;
export type BlackjackSeat = z.infer<typeof seatSchema>;
export type BlackjackHandCards = z.infer<typeof handSchema>;
export type BlackjackMove =
  "hit" | "stand" | "double" | "split" | "insurance" | "no_insurance" | "dealer";

/** Seconds until an ISO deadline, never below zero. */
export function secondsUntil(deadline: string | undefined, now: number) {
  return deadline ? Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1000)) : null;
}

export async function getBlackjackHand(betId: string, signal?: AbortSignal) {
  return read(
    await request(`/bets/${encodeURIComponent(betId)}/blackjack`, signal ? { signal } : undefined),
    blackjackHandSchema,
  );
}

// ----- Cards -----

const SUITS: Record<string, string> = { S: "♠", H: "♥", D: "♦", C: "♣" };

/** "TH" → { rank: "10", suit: "♥", red: true }; "??" is the face-down card. */
export function cardFace(code: string) {
  if (code === "??") return null;
  const rank = code[0] === "T" ? "10" : code[0]!;
  const suit = code[1]!;
  return { rank, suit: SUITS[suit] ?? suit, red: suit === "H" || suit === "D" };
}

const value = (code: string) =>
  code[0] === "A" ? 1 : "TJQK".includes(code[0]!) ? 10 : Number(code[0]);

/** The hand you're playing now: your first one that isn't finished. */
export function currentHand(seat: BlackjackSeat) {
  return seat.hands?.findIndex((h) => !h.done) ?? -1;
}

/** Which moves your current hand allows under the rules (the server checks again). */
export function allowedMoves(rules: BlackjackRules, seat: BlackjackSeat) {
  const index = currentHand(seat);
  const hand = index >= 0 ? seat.hands![index]! : null;
  if (!hand) return { double: false, split: false };
  const two = hand.cards.length === 2;
  const fromSplit = (seat.hands?.length ?? 1) > 1;
  const hard = hand.cards.reduce((sum, c) => sum + value(c), 0);
  const doubleOk =
    two &&
    (!fromSplit || rules.doubleAfterSplit) &&
    (rules.doubleOn === "any" ||
      (hard >= 9 && hard <= 11) ||
      (hand.total >= 9 && hand.total <= 11));
  const splitsUsed = (seat.hands?.length ?? 1) - 1;
  const splitAces = fromSplit && hand.cards[0]?.[0] === "A";
  const splitOk =
    two &&
    splitsUsed < rules.maxSplits &&
    !splitAces &&
    value(hand.cards[0]!) === value(hand.cards[1]!);
  return { double: doubleOk, split: splitOk };
}

/** Whether it's this seat's turn to decide. */
export function myTurn(hand: BlackjackHand, seat: BlackjackSeat) {
  if (hand.phase === "insurance") return !!seat.insuranceOpen;
  if (hand.phase !== "play" || currentHand(seat) < 0) return false;
  return hand.turnSeat === undefined || hand.turnSeat === seat.seat;
}

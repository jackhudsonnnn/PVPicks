import { formatCents } from "@/lib/money";

/** What the card games (Jackblack, Hold'em) share on the client: cards, timers and players' running results. */

const SUITS: Record<string, string> = { S: "♠", H: "♥", D: "♦", C: "♣" };

/** "TH" → { rank: "10", suit: "♥", red: true }; "??" is the face-down card (null). */
export function cardFace(code: string) {
  if (code === "??") return null;
  const rank = code[0] === "T" ? "10" : code[0]!;
  const suit = code[1]!;
  return { rank, suit: SUITS[suit] ?? suit, red: suit === "H" || suit === "D" };
}

/** Seconds until an ISO deadline, never below zero. */
export function secondsUntil(deadline: string | undefined, now: number) {
  return deadline ? Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1000)) : null;
}

/** Points with a sign: "+1.50", "-2.00", "0.00". */
export function signed(cents: number) {
  return `${cents > 0 ? "+" : ""}${formatCents(cents)}`;
}

/** A player's chip on the table felt during a card game. */
export type SeatChip = { label: string; tone: "pick" | "pass" | "win" | "loss" | "washed" };

/** A finished result as a felt chip: "Up 1.50", "Down 2.00" or "Even". */
export function resultChip(cents: number): SeatChip {
  if (cents === 0) return { label: "Even", tone: "pass" };
  return cents > 0
    ? { label: `Up ${formatCents(cents, true)}`, tone: "win" }
    : { label: `Down ${formatCents(-cents)}`, tone: "loss" };
}

import { toCents } from "@/lib/money";
import { PASS, type Bet } from "./types";

/** Every account starts with this many points. */
export const STARTING_POINTS = 100;

const inPot = (b: Bet, userId: string) =>
  b.participants.some((p) => p.userId === userId && p.selection !== PASS);

/**
 * Points balance: starting points, plus resolved results, minus wagers
 * currently sitting in a Pending pot. Active and Washed bets cost nothing.
 * Resolved results count even when you sat out, since a host or proposer can
 * receive leftover cents from a pot they weren't in.
 */
export function pointsBalance(userId: string, bets: Bet[]) {
  let total = STARTING_POINTS;
  for (const b of bets) {
    if (b.settled || b.forFun) continue;
    if (b.status === "pending" && inPot(b, userId)) total -= b.wager;
    if (b.status === "resolved") total += b.deltas?.[userId] ?? 0;
  }
  return toCents(total);
}

/** Net resolved result for a user, optionally limited to one table. */
export function netResult(userId: string, bets: Bet[], tableId?: string) {
  return toCents(
    bets
      .filter((b) => b.status === "resolved" && !b.settled && (!tableId || b.tableId === tableId))
      .reduce((sum, b) => sum + (b.deltas?.[userId] ?? 0), 0),
  );
}

/** Wins and losses on tickets you played (leftover cents from a pot you sat out don't count). */
export function record(userId: string, bets: Bet[]) {
  let w = 0;
  let l = 0;
  for (const b of bets) {
    if (b.status !== "resolved" || b.forFun || !inPot(b, userId)) continue;
    const d = b.deltas?.[userId];
    if (d === undefined) continue;
    if (d > 0) w++;
    else if (d < 0) l++;
  }
  return { w, l };
}

/** Points read like dollars: always two decimals (1.00, -4.25). */
export function formatPts(n: number, signed = false) {
  const cents = toCents(n) || 0; // `|| 0` turns -0 into 0
  const s = cents.toFixed(2);
  if (signed && cents > 0) return `+${s}`;
  return s;
}

export function formatClock(sec: number) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

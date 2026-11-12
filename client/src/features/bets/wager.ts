import { toCents } from "@/lib/money";

/** Bet amounts are dollars-style points, kept to the nearest cent. */
export const WAGER_MIN = 0.1;
export const WAGER_MAX = 10;
export const WAGER_DEFAULT = 1;
/** Step buttons on the proposal sheet. */
export const WAGER_STEPS = [0.01, 0.1, 1] as const;

function clampWager(n: number) {
  return toCents(Math.min(WAGER_MAX, Math.max(WAGER_MIN, n)));
}

/** Add a step to the current wager, never going past the max. */
export function stepWager(current: number, step: number) {
  return clampWager(current + step);
}

export const canStep = (current: number, step: number) => toCents(current + step) <= WAGER_MAX;

export function isValidWager(n: number) {
  return Number.isFinite(n) && toCents(n) === n && n >= WAGER_MIN && n <= WAGER_MAX;
}

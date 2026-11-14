/** Wager limits the server enforces for non-4fun bets, in cents (0.10 to 10.00 points). */
export const WAGER_MIN_CENTS = 10;
export const WAGER_MAX_CENTS = 1000;
export const WAGER_DEFAULT_CENTS = 100;
/** Step buttons on the proposal sheet: +0.01, +0.10, +1.00. */
export const WAGER_STEPS_CENTS = [1, 10, 100] as const;

/** Adds a step, never going past the max. */
export function stepWager(currentCents: number, stepCents: number) {
  return Math.min(WAGER_MAX_CENTS, Math.max(WAGER_MIN_CENTS, currentCents + stepCents));
}

export const canStep = (currentCents: number, stepCents: number) =>
  currentCents + stepCents <= WAGER_MAX_CENTS;

export const isValidWager = (cents: number) =>
  Number.isSafeInteger(cents) && cents >= WAGER_MIN_CENTS && cents <= WAGER_MAX_CENTS;

/** Pick windows offered on the proposal sheet; the server accepts 30 to 600 seconds. */
export const PICK_WINDOWS = [
  { label: "30s", sec: 30 },
  { label: "1m", sec: 60 },
  { label: "2m", sec: 120 },
  { label: "5m", sec: 300 },
  { label: "10m", sec: 600 },
] as const;
export const PICK_WINDOW_DEFAULT = 120;

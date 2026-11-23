/** Wager limits the server enforces for non-4fun bets, in cents (0.10 to 10.00 points). */
export const WAGER_MIN_CENTS = 10;
export const WAGER_MAX_CENTS = 1000;
export const WAGER_DEFAULT_CENTS = 100;

/** Pick windows offered on the proposal sheet; the server accepts 30 to 600 seconds. */
export const PICK_WINDOWS = [
  { label: "30s", sec: 30 },
  { label: "1m", sec: 60 },
  { label: "2m", sec: 120 },
  { label: "5m", sec: 300 },
  { label: "10m", sec: 600 },
] as const;
export const PICK_WINDOW_DEFAULT = 30;

/**
 * No new entries this close to lock: no first pick, no Jackblack join or leave (the server enforces it). Anyone
 * already in can still change or clear their pick.
 */
export const LATE_ENTRY_SEC = 10;

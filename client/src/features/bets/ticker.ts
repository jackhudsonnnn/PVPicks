import type { Game } from "./types";

/**
 * Repeats items in whole cycles until at least 16 slots are filled, so the sliding strip's seam stays
 * continuous even with one item.
 */
export function repeatForTicker<T>(items: readonly T[]): T[] {
  if (items.length === 0) return [];
  const size = Math.ceil(16 / items.length) * items.length;
  return Array.from({ length: size }, (_, index) => items[index % items.length]).filter(
    (item): item is T => item !== undefined,
  );
}

/** Whole cycles keep the seam continuous even with only one game in the feed. */
export function tickerWindow(games: readonly Game[]): Game[] {
  return repeatForTicker([...games].sort((a, b) => Number(b.live) - Number(a.live)));
}

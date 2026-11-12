import type { Game } from "./types";

/** Whole cycles keep the seam continuous even with only one game in the feed. */
export function tickerWindow(games: readonly Game[]): Game[] {
  if (games.length === 0) return [];
  const sorted = [...games].sort((a, b) => Number(b.live) - Number(a.live));
  const size = Math.ceil(16 / sorted.length) * sorted.length;
  return Array.from({ length: size }, (_, index) => sorted[index % sorted.length]).filter(
    (game): game is Game => game !== undefined,
  );
}
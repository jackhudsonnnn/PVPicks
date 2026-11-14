/**
 * Repeats items in whole cycles until at least 16 slots are filled, so the sliding ticker strip's seam stays
 * continuous even with one item.
 */
export function repeatForTicker<T>(items: readonly T[]): T[] {
  if (items.length === 0) return [];
  const size = Math.ceil(16 / items.length) * items.length;
  return Array.from({ length: size }, (_, index) => items[index % items.length]).filter(
    (item): item is T => item !== undefined,
  );
}

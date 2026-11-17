import { useState } from "react";

/** How many rows or cards a long list mounts at first, and adds each time you near its end. */
export const CLIENT_PAGE = 20;

/**
 * Shows a long, already-fetched list a page at a time, so hundreds of cards or rows don't all mount at once.
 * `more` adds the next page; the count never shrinks, so answering one item doesn't hide others.
 */
export function useClientPages<T>(items: readonly T[], size = CLIENT_PAGE) {
  const [count, setCount] = useState(size);
  return {
    shown: items.slice(0, count),
    hasMore: items.length > count,
    more: () => setCount((current) => current + size),
  };
}

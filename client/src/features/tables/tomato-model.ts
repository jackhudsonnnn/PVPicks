import type { TomatoThrow } from "./api";

/**
 * Throws to animate: not yet shown, and thrown after this screen started watching, so opening a table doesn't
 * replay old ones. Oldest first.
 */
export function newThrows(throws: TomatoThrow[], seen: ReadonlySet<string>, sinceMs: number) {
  return throws.filter((t) => !seen.has(t.id) && Date.parse(t.thrownAt) >= sinceMs);
}

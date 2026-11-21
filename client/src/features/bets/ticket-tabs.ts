/**
 * Which Tickets tab to show when some have no tickets. An empty tab is disabled; if the one you're on empties
 * (your last Active ticket locks), you move to the next tab with tickets, or the previous one if none come
 * after it. If every tab is empty you stay put.
 */
export function ticketTab<T>(order: readonly T[], current: T, empty: ReadonlySet<T>): T {
  if (!empty.has(current)) return current;
  const at = order.indexOf(current);
  const after = order.slice(at + 1).find((t) => !empty.has(t));
  const before = order
    .slice(0, at)
    .reverse()
    .find((t) => !empty.has(t));
  return after ?? before ?? current;
}

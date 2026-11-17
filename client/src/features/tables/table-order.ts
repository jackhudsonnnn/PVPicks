import type { TableSummary } from "./api";

/** When a table last saw action for you: its most recent bet, or when you joined if that's later. */
function lastActivity(table: TableSummary) {
  const joined = Date.parse(table.joinedAt);
  return table.lastBetAt ? Math.max(Date.parse(table.lastBetAt), joined) : joined;
}

/**
 * Tables by most recent bet, newest first (ties by name). The order comes from the fetched list, which bets don't
 * refresh, so it only changes when the list is fetched again (opening a page, joining or leaving a table), not
 * while you're looking at it.
 */
export function byRecentBet(tables: readonly TableSummary[]): TableSummary[] {
  return [...tables].sort(
    (a, b) => lastActivity(b) - lastActivity(a) || a.name.localeCompare(b.name),
  );
}

/** Table requests by when they were sent, newest first. */
export function newestFirst<T extends { createdAt: string; id: string }>(
  requests: readonly T[],
): T[] {
  return [...requests].sort(
    (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || a.id.localeCompare(b.id),
  );
}

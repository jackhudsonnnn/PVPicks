// TODO(backend-logic): code generation and copy fan-out run server-side (Kafka quickpick.copied.v1).
import type { Table } from "@/features/table/types";
import type { Bet } from "@/features/bets/types";
import { isHost } from "./hosts";

/**
 * Quick Picks: a table shares its proposals with a 6-character code; other tables' hosts
 * subscribe with it. Every new original bet at the source auto-posts as an exact copy at each
 * subscriber. Each table still only bets among itself. Copies follow the source's result,
 * unless a host at the copy's table overrides it (wash).
 */

export const CODE_LENGTH = 6;
/** No 0/O or 1/I/L, so codes are easy to read out loud. */
export const CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export function makeCode(rand: () => number = Math.random) {
  let s = "";
  for (let i = 0; i < CODE_LENGTH; i++) s += CODE_CHARS[Math.floor(rand() * CODE_CHARS.length)];
  return s;
}

/** Uppercases and drops spaces/dashes so "k4r-7qx" matches "K4R7QX". */
export const normalizeCode = (raw: string) => raw.toUpperCase().replace(/[\s-]/g, "");

const isQuickPicksOn = (t: Table) => !!t.quickPicksOn;
export const isSharing = (t: Table) => !!t.quickPicksOn && !!t.shareCode;
export const subscriptionsOf = (t: Table) => (t.quickPicksOn ? (t.subscribedTo ?? []) : []);

/** Tables currently subscribed to `sourceId`. */
export const subscribersOf = (tables: Table[], sourceId: string) =>
  tables.filter((t) => subscriptionsOf(t).includes(sourceId));

/** Why `actor` can't subscribe `table` with `code`, or the source table when it's fine. */
export function subscribeCheck(
  tables: Table[],
  table: Table,
  actor: string,
  code: string,
): { error: string } | { source: Table } {
  if (!isHost(table, actor)) return { error: "Only hosts can subscribe." };
  if (!isQuickPicksOn(table)) return { error: "Turn on Quick Picks first." };
  const c = normalizeCode(code);
  if (c.length !== CODE_LENGTH) return { error: `Codes are ${CODE_LENGTH} characters.` };
  const source = tables.find((t) => isSharing(t) && t.shareCode === c);
  if (!source) return { error: "No table is sharing with that code." };
  if (source.id === table.id) return { error: "That's this table's own code." };
  if (subscriptionsOf(table).includes(source.id))
    return { error: `Already subscribed to ${source.name}.` };
  return { source };
}

/** Copy ids are deterministic so the same bet never lands twice at one table. */
export const copyId = (betId: string, tableId: string) => `${betId}@${tableId}`;

/**
 * The copy of `bet` for `target`, or null when it shouldn't go there.
 * Only originals are shared (no copies of copies), and a table that turned off U2Pick
 * doesn't get U2Pick copies. Copies are exact: same question, wager, 4fun, options and timer.
 */
export function copyFor(bet: Bet, source: Table, target: Table, at: string): Bet | null {
  if (bet.quickPick || bet.status !== "active" || target.id === source.id) return null;
  if (bet.modeId === "u2pick" && target.u2pickOff) return null;
  return {
    ...bet,
    id: copyId(bet.id, target.id),
    tableId: target.id,
    participants: [],
    createdAt: at,
    quickPick: { sourceTableId: source.id, sourceBetId: bet.id, sourceName: source.name },
  };
}

/** Copies still waiting on the source's result. */
export const followsSource = (copy: Bet) =>
  !!copy.quickPick && !copy.quickPick.overridden && copy.status === "pending";

/**
 * A host at the copy's table can wash it if the source's call looks wrong: while it waits,
 * or after it resolved, as long as the table hasn't settled points for it yet.
 */
export function canOverride(table: Table, bet: Bet, userId: string) {
  if (!bet.quickPick || bet.quickPick.overridden || bet.tableId !== table.id) return false;
  if (!isHost(table, userId) || bet.settled) return false;
  return bet.status === "pending" || bet.status === "resolved";
}

export const OVERRIDE_REASON = "Host overrode the Quick Pick result";

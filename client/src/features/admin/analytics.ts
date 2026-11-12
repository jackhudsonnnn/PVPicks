import { toCents } from "@/lib/money";
import { getMode } from "@/features/bets/modes";
import { PASS, type Bet, type BetStatus } from "@/features/bets/types";
import type { Message, Table } from "@/features/table/types";
import type { PlayerAccountId } from "@/features/auth/demo-accounts";

/**
 * Numbers behind the Admin dashboards. Pure functions over a player's data so they're testable
 * and a real analytics feed can replace the sample worlds later.
 */

/** Every resolved points ticket should net to zero. Lists tickets that don't. */
export function zeroSumAudit(bets: Bet[]) {
  const checked = bets.filter((b) => b.status === "resolved" && !b.forFun && b.deltas);
  const broken = checked
    .filter((b) => Math.abs(Object.values(b.deltas!).reduce((s, d) => s + d, 0)) > 0.005)
    .map((b) => b.id);
  return { checked: checked.length, broken };
}

/** Points that changed hands: the winners' side of every resolved ticket. */
export const pointsMoved = (bets: Bet[]) =>
  toCents(
    bets
      .filter((b) => b.status === "resolved" && !b.forFun)
      .reduce(
        (s, b) => s + Object.values(b.deltas ?? {}).reduce((x, d) => x + Math.max(0, d), 0),
        0,
      ),
  );

export function modeBreakdown(bets: Bet[]) {
  const counts = new Map<string, number>();
  for (const b of bets) counts.set(b.modeId, (counts.get(b.modeId) ?? 0) + 1);
  return [...counts]
    .map(([id, count]) => ({ id, name: getMode(id as Bet["modeId"]).name, count }))
    .sort((a, b) => b.count - a.count);
}

const STATUSES: BetStatus[] = ["active", "pending", "resolved", "washed"];

export function statusBreakdown(bets: Bet[]) {
  return STATUSES.map((status) => ({
    status,
    count: bets.filter((b) => b.status === status).length,
  }));
}

/** How a player's finished tickets went. Pass = in the bet with no pick. */
export function outcomes(userId: string, bets: Bet[]) {
  const out = { win: 0, loss: 0, wash: 0, pass: 0 };
  for (const b of bets) {
    if (b.status !== "resolved" && b.status !== "washed") continue;
    const p = b.participants.find((x) => x.userId === userId);
    if (!p) continue;
    if (p.selection === PASS) out.pass++;
    else if (b.status === "washed") out.wash++;
    else {
      const d = b.deltas?.[userId] ?? 0;
      if (d > 0) out.win++;
      else if (d < 0) out.loss++;
      else out.wash++;
    }
  }
  return out;
}

/** Tickets per day from labels like "Oct 3 7:42 PM"; plain times count as today. */
export function ticketsByDay(bets: Bet[]) {
  const counts = new Map<string, number>();
  for (const b of bets) {
    const m = /^([A-Z][a-z]{2}) (\d{1,2}) /.exec(b.createdAt);
    const day = m ? `${m[1]} ${m[2]}` : "Today";
    counts.set(day, (counts.get(day) ?? 0) + 1);
  }
  const num = (d: string) => (d === "Today" ? Infinity : Number(d.split(" ")[1]));
  return [...counts]
    .map(([day, count]) => ({ day, count }))
    .sort((a, b) => num(a.day) - num(b.day));
}

/** Tables with the most chat, for the "busiest tables" bar chart. */
export function busiestTables(tables: Table[], messages: Message[], n = 6) {
  const counts = new Map<string, number>();
  for (const m of messages) counts.set(m.tableId, (counts.get(m.tableId) ?? 0) + 1);
  return tables
    .map((t) => ({ name: `${t.emoji} ${t.name}`, messages: counts.get(t.id) ?? 0 }))
    .sort((a, b) => b.messages - a.messages)
    .slice(0, n);
}

export interface LivePoint {
  t: number;
  newbie: number;
  intermediate: number;
  pro: number;
}

/** Simulated actions per tick for each sample player (picks, chats, reactions). */
export const LIVE_RATE: Record<PlayerAccountId, [number, number]> = {
  newbie: [0, 1],
  intermediate: [0, 3],
  pro: [6, 16],
};

/** Adds one live sample and keeps the last `max` points. */
export function liveTick(prev: LivePoint[], rand: () => number, max = 30): LivePoint[] {
  const at = (id: PlayerAccountId) => {
    const [lo, hi] = LIVE_RATE[id];
    return lo + Math.round(rand() * (hi - lo));
  };
  const t = (prev[prev.length - 1]?.t ?? 0) + 1;
  return [
    ...prev,
    { t, newbie: at("newbie"), intermediate: at("intermediate"), pro: at("pro") },
  ].slice(-max);
}

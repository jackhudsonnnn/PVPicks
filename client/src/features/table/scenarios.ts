import { toCents } from "@/lib/money";
import { inThePot } from "@/features/bets/payout";
import type { Bet } from "@/features/bets/types";

/**
 * Peers tab balances. Only each bet's real result (`deltas`) is stored; these
 * views are worked out from it on the fly and never saved.
 */
export type Scenario = "true" | "settle";

export const SCENARIOS: { id: Scenario; label: string; description: string }[] = [
  {
    id: "true",
    label: "True",
    description:
      "You can view this balance as the worst-case scenario, which assumes you/your peers lose every pending bet. When the host settles the table, these values decrement by your settle balance.",
  },
  {
    id: "settle",
    label: "Settle",
    description:
      "P2Picks is zero sum, in that one player's loss in points is another player's gain. When the host settles the table, these values are reset to 0 and each player is shown who to send/recieve money to/from.",
  },
];

export type ScenarioNets = Record<Scenario, number>;

/**
 * Each member's balance at the table:
 * - settle: unsettled results only (zero-sum between peers).
 * - true: settle minus the wagers they have in pending pots.
 * Active tickets are left out (picks are hidden and nothing is in a pot yet).
 */
export function peerScenarios(bets: Bet[], tableId: string): Map<string, ScenarioNets> {
  const out = new Map<string, ScenarioNets>();
  const add = (id: string, key: Scenario, n: number) => {
    const row = out.get(id) ?? { true: 0, settle: 0 };
    row[key] = toCents(row[key] + n);
    out.set(id, row);
  };

  for (const b of bets) {
    if (b.tableId !== tableId || b.forFun) continue;
    if (b.status === "resolved" && !b.settled) {
      for (const [id, d] of Object.entries(b.deltas ?? {})) {
        add(id, "true", d);
        add(id, "settle", d);
      }
      continue;
    }
    if (b.status !== "pending") continue;
    for (const p of inThePot(b.participants)) {
      add(p.userId, "settle", 0);
      add(p.userId, "true", -b.wager);
    }
  }
  return out;
}

/**
 * Memo for peerScenarios. Reducers replace `state.bets` on every bet change, so the
 * array itself acts as the bets version (settling a table also swaps it).
 * TODO(backend): replace with server-kept per-table ledger sums (keyed by last
 * settlement id + bets version) pushed over the table feed.
 */
const scenarioCache = new WeakMap<Bet[], Map<string, Map<string, ScenarioNets>>>();
export function cachedPeerScenarios(bets: Bet[], tableId: string): Map<string, ScenarioNets> {
  let byTable = scenarioCache.get(bets);
  if (!byTable) scenarioCache.set(bets, (byTable = new Map()));
  let nets = byTable.get(tableId);
  if (!nets) byTable.set(tableId, (nets = peerScenarios(bets, tableId)));
  return nets;
}

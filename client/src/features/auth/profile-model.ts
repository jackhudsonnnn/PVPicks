import type { BackendBet } from "./backend-api";
import type { TomatoThrow } from "./tables-api";

/**
 * Bets at a table that involve someone, as far as the viewer can see: ones they proposed, and ones they joined once
 * picks are public (after lock). For the viewer, their own Active participation counts too.
 */
export function betsInvolving(bets: BackendBet[], userId: string, viewerId: string): BackendBet[] {
  return bets
    .filter(
      (bet) =>
        bet.proposerId === userId ||
        bet.picks?.some((pick) => pick.userId === userId) ||
        bet.noGuessUserIds?.includes(userId) ||
        (userId === viewerId && bet.status === "ACTIVE" && bet.myParticipation !== "not_joined"),
    )
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export interface ProfileStats {
  /** Resolved, non-4fun bets they had a pick in. */
  played: number;
  won: number;
  lost: number;
  /** Their total result across those bets, in cents. */
  netCents: number;
}

export function profileStats(bets: BackendBet[], userId: string): ProfileStats {
  const stats: ProfileStats = { played: 0, won: 0, lost: 0, netCents: 0 };
  for (const bet of bets) {
    if (bet.status !== "SETTLED" || bet.forFun) continue;
    if (!bet.picks?.some((pick) => pick.userId === userId)) continue;
    const delta = bet.deltasCents?.[userId] ?? 0;
    stats.played += 1;
    if (delta > 0) stats.won += 1;
    if (delta < 0) stats.lost += 1;
    stats.netCents += delta;
  }
  return stats;
}

/**
 * Throws to animate: not yet shown, and thrown after this screen started watching, so opening a table doesn't
 * replay old ones. Oldest first.
 */
export function newThrows(throws: TomatoThrow[], seen: ReadonlySet<string>, sinceMs: number) {
  return throws.filter((t) => !seen.has(t.id) && Date.parse(t.thrownAt) >= sinceMs);
}

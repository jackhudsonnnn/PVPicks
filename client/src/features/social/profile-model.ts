import type { Bet } from "@/features/bets";

/**
 * Bets at a table that involve someone, as far as the viewer can see: ones they proposed, and ones they joined once
 * picks are public (after lock). For the viewer, their own Active participation counts too.
 */
export function betsInvolving(bets: Bet[], userId: string, viewerId: string): Bet[] {
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

export function profileStats(bets: Bet[], userId: string): ProfileStats {
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

import type { Bet } from "./api";

/** Resolved, non-4fun bets whose points a host still has to settle. Washed bets move no points. */
export function awaitingPointsSettlement(bets: Bet[]): number {
  return bets.filter((bet) => bet.status === "SETTLED" && !bet.forFun && !bet.pointsSettled).length;
}

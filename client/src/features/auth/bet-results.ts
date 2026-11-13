import type { BackendBet, BackendTable } from "./backend-api";

/**
 * Whether `userId` may call the result of a locked U2Pick, mirroring the backend's
 * `U2PickSettler`. It only decides whether to offer the control: the server rechecks
 * authority (and excludes frozen members) when the result is called.
 */
export function canCallResult(bet: BackendBet, table: BackendTable, userId: string): boolean {
  if (bet.modeId !== "u2pick" || bet.status !== "PENDING") return false;
  if (!table.memberIds.includes(userId)) return false;
  const isHost = table.hostIds.includes(userId);
  const inPot = new Set((bet.picks ?? []).map((pick) => pick.userId));
  switch (bet.settledBy ?? "hosts") {
    case "anyone":
      return true;
    case "hosts":
      return isHost;
    case "me":
      return userId === bet.proposerId;
    case "non_participants":
      return !inPot.has(userId) || (table.memberIds.every((id) => inPot.has(id)) && isHost);
  }
}

/** Resolved, non-4fun bets whose points a host still has to settle. Washed bets move no points. */
export function awaitingPointsSettlement(bets: BackendBet[]): number {
  return bets.filter((bet) => bet.status === "SETTLED" && !bet.forFun && !bet.pointsSettled).length;
}

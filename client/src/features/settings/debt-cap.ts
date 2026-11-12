// TODO(backend-logic): server must enforce cap on propose/pick/lock; client check is advisory.
import { cachedPeerScenarios } from "@/features/table/scenarios";
import { settleGroups } from "@/features/social/settle-groups";
import type { AppState } from "@/features/store/state";

export const DEFAULT_DEBT_CAP: number | null = null;

export function isDebtCap(value: unknown): value is number | null {
  return value === null || (typeof value === "number" && Number.isSafeInteger(value) && value >= 0);
}

/** Positive tables/people never offset debt elsewhere. Work in integer cents. */
export function userDebt(state: AppState, userId: string): number {
  let cents = 0;
  for (const table of state.tables) {
    if (!table.memberIds.includes(userId)) continue;
    const balance = cachedPeerScenarios(state.bets, table.id).get(userId)?.true ?? 0;
    cents += Math.max(0, -Math.round(balance * 100));
  }
  for (const group of settleGroups(state.messages, userId)) {
    cents += Math.max(0, -Math.round(group.net * 100));
  }
  return cents / 100;
}

export const canChangeDebtCap = (state: AppState, userId: string) => userDebt(state, userId) === 0;

export function canRiskWager(state: AppState, userId: string, wager: number, forFun?: boolean) {
  const cap = state.debtCaps?.[userId] ?? DEFAULT_DEBT_CAP;
  return forFun || cap === null || Math.round((userDebt(state, userId) + wager) * 100) <= cap * 100;
}

// TODO(backend): persist caps on accounts and validate changes/locks atomically against the
// current ledger. Browser state is a prototype safeguard, not an enforceable financial limit.
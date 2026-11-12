// TODO(backend-logic): authoritative rules here (permissions, lock, payout, settle, debt-cap, blocks) must run
// server-side; the client reducer should only apply server-confirmed events/optimistic updates.
import { settleBet, type RemainderOptions } from "@/features/bets/payout";
import { applyPick, isValidPick } from "@/features/bets/picks";
import { PASS, type Bet } from "@/features/bets/types";
import { canSettleCustom } from "@/features/bets/u2pick";
import { canProposeMode, hasPermission, type MemberPermission } from "@/features/table/hosts";
import {
  OVERRIDE_REASON,
  canOverride,
  copyFor,
  followsSource,
  isSharing,
  subscribersOf,
} from "@/features/table/quick-picks";
import type { Table } from "@/features/table/types";
import { canRiskWager } from "@/features/settings/debt-cap";
import {
  findTable,
  nameOf,
  post,
  systemLine,
  updateBet,
  type ActionOf,
  type AppState,
} from "./state";

/** Host-set member permissions, checked here so every path (UI or sample data) obeys them. */
export function allowedAt(
  state: AppState,
  tableId: string,
  userId: string,
  perm: MemberPermission,
) {
  const t = findTable(state, tableId);
  return !!t && hasPermission(t, userId, perm);
}

export function canBetOn(state: AppState, betId: string, userId: string) {
  const bet = state.bets.find((b) => b.id === betId);
  return !!bet && allowedAt(state, bet.tableId, userId, "bets");
}

/** The table's leftover-cent rule for a bet; fixed onto the bet when its picks lock. */
function remainderAt(t: Table | undefined, bet: Bet): RemainderOptions {
  return {
    mode: t?.remainderMode ?? "host",
    hostId: t?.hostIds[0] ?? bet.proposerId,
    proposerId: bet.proposerId,
    seed: bet.id,
  };
}

/** Scores one pending bet with the leftover-cent rule fixed when it locked, and posts the chat line. */
function applySettle(
  state: AppState,
  bet: Bet,
  winningChoice: string | null,
  at: string,
  by: string,
): AppState {
  const t = findTable(state, bet.tableId);
  const raw = settleBet(
    bet.participants,
    bet.wager,
    bet.choices,
    winningChoice,
    bet.remainder ?? remainderAt(t, bet),
  );
  // 4fun: the result is shown but no points move and nothing is recorded.
  const r = bet.forFun ? { ...raw, deltas: {} } : raw;
  const next = updateBet(state, bet.id, (b) => ({
    ...b,
    status: r.status,
    winningChoice: winningChoice ?? undefined,
    washReason: r.status === "washed" ? r.reason : undefined,
    deltas: r.deltas,
    closedAt: at,
  }));
  const text =
    r.status === "resolved"
      ? `Resolved: ${winningChoice} wins${by}`
      : `Washed: ${r.reason.toLowerCase()}, wagers returned${by}`;
  return post(next, systemLine(bet.tableId, text, at));
}

/** Settles a bet and every Quick Pick copy still following it. */
function settleWithCopies(
  state: AppState,
  bet: Bet,
  winningChoice: string | null,
  at: string,
  by: string,
) {
  let next = applySettle(state, bet, winningChoice, at, by);
  for (const copy of state.bets) {
    if (copy.quickPick?.sourceBetId !== bet.id || !followsSource(copy)) continue;
    next = applySettle(
      next,
      copy,
      winningChoice,
      at,
      ` (Quick Pick, called by ${copy.quickPick.sourceName})`,
    );
  }
  return next;
}

/** Adds a Quick Pick copy of `bet` at `target` (skipped if it shouldn't go there or already did). */
export function postCopy(state: AppState, bet: Bet, source: Table, target: Table, at: string) {
  const copy = copyFor(bet, source, target, at);
  if (!copy || state.bets.some((b) => b.id === copy.id)) return state;
  return post(
    { ...state, bets: [...state.bets, copy] },
    { tableId: target.id, kind: "bet", authorId: bet.proposerId, betId: copy.id, at },
  );
}

export type BetAction = ActionOf<
  "tick" | "join" | "pick" | "propose" | "settle" | "gameResult" | "overrideQuickPick"
>;

export function betsReducer(state: AppState, action: BetAction): AppState {
  switch (action.type) {
    case "tick": {
      let changed = false;
      const locked: Bet[] = [];
      let exposure = state;
      const bets = state.bets.map((b) => {
        if (b.status !== "active") return b;
        changed = true;
        const next = Math.max(0, b.closesInSec - 1);
        if (next > 0) return { ...b, closesInSec: next };
        const nb = {
          ...b,
          participants: b.participants.map((p) =>
            p.selection !== PASS && !canRiskWager(exposure, p.userId, b.wager, b.forFun)
              ? { ...p, selection: PASS }
              : p,
          ),
          closesInSec: 0,
          status: "pending" as const,
          lockedAt: action.at,
          remainder: remainderAt(findTable(state, b.tableId), b),
        };
        exposure = updateBet(exposure, b.id, () => nb);
        const skipped = b.participants.filter((p, i) => p.selection !== PASS && nb.participants[i]?.selection === PASS);
        for (const p of skipped) {
          exposure = post(exposure, systemLine(b.tableId, `${nameOf(state, p.userId)} sat out: debt cap reached`, action.at));
        }
        locked.push(nb);
        return nb;
      });
      if (!changed) return state;
      return post(
        { ...exposure, bets },
        ...locked.map((b) =>
          systemLine(b.tableId, "Picks locked, wagers are in the pot", action.at),
        ),
      );
    }
    case "join": {
      if (!canBetOn(state, action.betId, action.userId)) return state;
      const selection = action.selection ?? PASS;
      const bet = state.bets.find((b) => b.id === action.betId);
      if (bet && selection !== PASS && !canRiskWager(state, action.userId, bet.wager, bet.forFun)) return state;
      return updateBet(state, action.betId, (b) =>
        b.status !== "active" ||
        !isValidPick(b, selection) ||
        b.participants.some((p) => p.userId === action.userId)
          ? b
          : { ...b, participants: [...b.participants, { userId: action.userId, selection }] },
      );
    }
    case "pick":
      if (!canBetOn(state, action.betId, action.userId)) return state;
      {
        const bet = state.bets.find((b) => b.id === action.betId);
        if (bet && action.selection !== PASS && !canRiskWager(state, action.userId, bet.wager, bet.forFun)) return state;
      }
      return updateBet(state, action.betId, (b) =>
        b.status !== "active" || !isValidPick(b, action.selection)
          ? b
          : { ...b, participants: applyPick(b.participants, action.userId, action.selection) },
      );
    case "propose": {
      const t = findTable(state, action.bet.tableId);
      if (!t || !canProposeMode(t, action.bet.proposerId, action.bet.modeId)) return state;
      if (!canRiskWager(state, action.bet.proposerId, action.bet.wager, action.bet.forFun)) return state;
      const posted = post(
        { ...state, bets: [...state.bets, action.bet] },
        {
          tableId: t.id,
          kind: "bet",
          authorId: action.bet.proposerId,
          betId: action.bet.id,
          at: action.at,
        },
      );
      // Quick Picks: originals auto-post at every subscribed table.
      if (!isSharing(t)) return posted;
      return subscribersOf(state.tables, t.id).reduce(
        (acc, sub) => postCopy(acc, action.bet, t, sub, action.at),
        posted,
      );
    }
    case "settle": {
      // U2Pick bets are called by people; the proposer chose who.
      const bet = state.bets.find((b) => b.id === action.betId);
      if (!bet || bet.status !== "pending" || bet.quickPick || bet.modeId !== "u2pick")
        return state;
      const t = findTable(state, bet.tableId);
      if (!t || !canSettleCustom(bet, t, action.actorId)) return state;
      const who = action.actorId === state.meId ? "you" : nameOf(state, action.actorId);
      const settled = settleWithCopies(state, bet, action.winningChoice, action.at, ` (called by ${who})`);
      return updateBet(settled, bet.id, (b) => ({ ...b, calledBy: action.actorId }));
    }
    case "gameResult": {
      // Game bets settle from the sports feed. Quick Pick copies follow their source instead.
      const bet = state.bets.find((b) => b.id === action.betId);
      if (!bet || bet.status !== "pending" || bet.quickPick || bet.modeId === "u2pick")
        return state;
      return settleWithCopies(state, bet, action.winningChoice, action.at, "");
    }
    case "overrideQuickPick": {
      const bet = state.bets.find((b) => b.id === action.betId);
      const t = bet && findTable(state, bet.tableId);
      if (!bet?.quickPick || !t || !canOverride(t, bet, action.actorId)) return state;
      const source = bet.quickPick;
      const next = updateBet(state, bet.id, (b) => ({
        ...b,
        status: "washed",
        washReason: OVERRIDE_REASON,
        winningChoice: undefined,
        deltas: {},
        closedAt: b.closedAt ?? action.at,
        quickPick: { ...source, overridden: true },
      }));
      return post(
        next,
        systemLine(
          t.id,
          `${nameOf(state, action.actorId)} overrode a Quick Pick from ${source.sourceName}. It's a wash, wagers returned`,
          action.at,
        ),
      );
    }
  }
}

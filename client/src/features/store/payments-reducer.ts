// TODO(backend-logic): authoritative rules here (permissions, lock, payout, settle, debt-cap, blocks) must run
// server-side; the client reducer should only apply server-confirmed events/optimistic updates.
import { settlementLines } from "@/features/bets/payout";
import { settleGroups } from "@/features/social/settle-groups";
import { requiresReceiptReview, requiresSettleReview, settlePairKey, settleReviewKey } from "@/features/social/settle-changes";
import { isHost } from "@/features/table/hosts";
import {
  canAskForgive,
  canConfirm,
  canDecline,
  canDispute,
  canForgive,
  canMarkSent,
  paymentsOf,
  setForgiveAsk,
  setPaymentStatus,
  type SettlementMessage,
} from "@/features/table/settlements";
import { findTable, post, type ActionOf, type AppState } from "./state";

const PAYMENT_RULE = {
  sent: canMarkSent,
  paid: canConfirm,
  forgiven: canForgive,
  unpaid: canDispute,
} as const;

/** Applies `change` to one settlement message; unchanged state if it returns null. */
function onSettlement(
  state: AppState,
  messageId: string,
  change: (m: SettlementMessage) => SettlementMessage | null,
): AppState {
  let changed = false;
  const messages = state.messages.map((m) => {
    if (m.id !== messageId || m.kind !== "settlement") return m;
    const next = change(m);
    if (!next) return m;
    changed = true;
    return next;
  });
  return changed ? { ...state, messages } : state;
}

export type PaymentAction = ActionOf<"settlePoints" | "payment" | "forgiveAsk" | "settlePerson" | "reviewSettleChange">;

/** Host "Settle points" and the sent / paid / forgive steps on each settle-up line. */
export function paymentsReducer(state: AppState, action: PaymentAction): AppState {
  switch (action.type) {
    case "reviewSettleChange": {
      if (action.actorId !== state.meId || action.actorId === action.otherUserId
          || state.settleChanges?.[settlePairKey(action.actorId, action.otherUserId)] !== action.change) return state;
      return { ...state, settleReviews: {
        ...state.settleReviews, [settleReviewKey(action.actorId, action.otherUserId)]: action.change,
      } };
    }
    case "settlePerson": {
      const group = settleGroups(state.messages, action.actorId).find((g) => g.otherUserId === action.otherUserId);
      if (!group) return state;
      if (action.expectedRevision !== undefined && action.expectedRevision !== group.revision) return state;
      if ((action.step === "sent" || action.step === "ask")
          && requiresSettleReview(state, action.actorId, action.otherUserId)) return state;
      const p = group.payment;
      const ok = group.net === 0
        ? action.step === "paid"
        : action.step === "ask"
          ? canAskForgive(p, action.actorId, action.now)
          : action.step === "decline"
            ? canDecline(p, action.actorId)
            : PAYMENT_RULE[action.step](p, action.actorId);
      if (!ok) return state;
      // Validate the net once, then update every contributing line together, even offsets.
      let next = state;
      for (const line of group.payments) {
        next = onSettlement(next, line.messageId, (m) =>
          action.step === "ask" || action.step === "decline"
            ? setForgiveAsk(m, line.lineUserId, { status: action.step === "ask" ? "open" : "declined", at: action.now })
            : setForgiveAsk(setPaymentStatus(m, line.lineUserId, action.step), line.lineUserId, undefined),
        );
      }
      return next;
    }
    case "settlePoints": {
      const me = state.meId;
      const table = findTable(state, action.tableId);
      if (!table || !isHost(table, me)) return state;
      const { lines, hostNet } = settlementLines(state.bets, table.id, me);
      if (lines.length === 0 && hostNet === 0) return state;
      return post(
        {
          ...state,
          bets: state.bets.map((b) =>
            b.tableId === table.id && b.status === "resolved" && !b.settled
              ? { ...b, settled: true, settledAt: action.at }
              : b,
          ),
        },
        { tableId: table.id, kind: "settlement", hostId: me, lines, hostNet, at: action.at },
      );
    }
    case "payment":
      if (action.status === "sent"
          && requiresReceiptReview(state, action.messageId, action.lineUserId, action.actorId)) return state;
      return onSettlement(state, action.messageId, (m) => {
        const p = paymentsOf(m).find((x) => x.lineUserId === action.lineUserId);
        if (!p || !PAYMENT_RULE[action.status](p, action.actorId)) return null;
        return setPaymentStatus(m, action.lineUserId, action.status);
      });
    case "forgiveAsk":
      if (action.answer === "ask"
          && requiresReceiptReview(state, action.messageId, action.lineUserId, action.actorId)) return state;
      return onSettlement(state, action.messageId, (m) => {
        const p = paymentsOf(m).find((x) => x.lineUserId === action.lineUserId);
        if (!p) return null;
        const ok =
          action.answer === "ask"
            ? canAskForgive(p, action.actorId, action.now)
            : canDecline(p, action.actorId);
        if (!ok) return null;
        return setForgiveAsk(m, action.lineUserId, {
          status: action.answer === "ask" ? "open" : "declined",
          at: action.now,
        });
      });
  }
}

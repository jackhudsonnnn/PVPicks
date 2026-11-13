import { betsReducer } from "./bets-reducer";
import { paymentsReducer } from "./payments-reducer";
import { socialReducer } from "./social-reducer";
import { hostReducer, membershipReducer } from "./table-reducer";
import type { Action, AppState } from "./state";
import { reconcileSettleChanges, settleReviewKey } from "@/features/social/settle-changes";
import { settleGroups } from "@/features/social/settle-groups";
import { canChangeDebtCap, isDebtCap } from "@/features/settings/debt-cap";

/**
 * The whole app's state machine. Pure: the same state and action always give the same
 * result, so a server can run the same rules and replay actions.
 */
export function reducer(state: AppState, action: Action): AppState {
  const next = reduceAction(state, action);
  if (next.messages === state.messages) return next;
  // Receipt actions close only their own line; never invalidate other in-flight receipts.
  const { messages, changes } = action.type === "payment" || action.type === "forgiveAsk"
    ? { messages: next.messages, changes: {} }
    : reconcileSettleChanges(state.messages, next.messages);
  const settleChanges = { ...next.settleChanges, ...changes };
  const settleReviews = { ...next.settleReviews };
  let refreshed = false;
  for (const [key, change] of Object.entries(state.settleChanges ?? {})) {
    if (key in changes) continue;
    const before = settleGroups(state.messages, change.fromId).find((group) => group.otherUserId === change.toId);
    const after = settleGroups(messages, change.fromId).find((group) => group.otherUserId === change.toId);
    if (!before || !after || before.net === after.net) continue;
    const updated = { ...change, before: before.net, after: after.net, revision: after.revision };
    for (const [actor, other] of [[change.fromId, change.toId], [change.toId, change.fromId]] as const) {
      const reviewKey = settleReviewKey(actor, other);
      if (settleReviews[reviewKey] === change) settleReviews[reviewKey] = updated;
    }
    settleChanges[key] = updated;
    refreshed = true;
  }
  if (!Object.keys(changes).length && !refreshed) return next;
  return { ...next, messages, settleChanges, settleReviews };
}

function reduceAction(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "loadDebtCap":
      return isDebtCap(action.cap)
        ? { ...state, debtCaps: { ...state.debtCaps, [state.meId]: action.cap } }
        : state;
    case "setDebtCap":
      if (action.userId !== state.meId || !isDebtCap(action.cap) || !canChangeDebtCap(state, action.userId)) return state;
      return { ...state, debtCaps: { ...state.debtCaps, [action.userId]: action.cap } };
    case "tick":
    case "join":
    case "pick":
    case "propose":
    case "settle":
    case "gameResult":
    case "overrideQuickPick":
      return betsReducer(state, action);
    case "chat":
    case "gif":
    case "react":
    case "updateProfile":
    case "follow":
    case "block":
    case "deleteAccount":
    case "loadBlocks":
    case "setInvitePrefs":
      return socialReducer(state, action);
    case "createTable":
    case "addMembers":
    case "inviteIn":
    case "respondInvite":
      return membershipReducer(state, action);
    case "updateTable":
    case "toggleHost":
    case "setRemainderMode":
    case "setHostsOnlyBets":
    case "setU2PickOff":
    case "setQuickPicks":
    case "setSharing":
    case "subscribe":
    case "unsubscribe":
    case "removeMember":
    case "deleteTable":
    case "setPermission":
      return hostReducer(state, action);
    case "settlePoints":
    case "reviewSettleChange":
    case "settlePerson":
    case "payment":
    case "forgiveAsk":
      return paymentsReducer(state, action);
  }
}

import { betsReducer } from "./bets-reducer";
import { paymentsReducer } from "./payments-reducer";
import { socialReducer } from "./social-reducer";
import { hostReducer, membershipReducer } from "./table-reducer";
import type { Action, AppState } from "./state";
import { reconcileSettleChanges } from "@/features/social/settle-changes";
import { canChangeDebtCap, isDebtCap } from "@/features/settings/debt-cap";

/**
 * The whole app's state machine. Pure: the same state and action always give the same
 * result, so a server can run the same rules and replay actions.
 */
export function reducer(state: AppState, action: Action): AppState {
  const next = reduceAction(state, action);
  // Receipt actions close only their own line; never invalidate other in-flight receipts.
  if (action.type === "payment" || action.type === "forgiveAsk") return next;
  if (next.messages === state.messages) return next;
  const { messages, changes } = reconcileSettleChanges(state.messages, next.messages);
  if (!Object.keys(changes).length) return next;
  return { ...next, messages, settleChanges: { ...next.settleChanges, ...changes } };
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
    case "settlePerson":
    case "payment":
    case "forgiveAsk":
      return paymentsReducer(state, action);
  }
}

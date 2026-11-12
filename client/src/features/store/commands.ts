// TODO(backend-middleware): this is THE integration seam. Each command should call the Spring API
// (see p2picks-backend-kt_v3 §9) and dispatch the server response; keep validation here only as UX pre-checks.
import type { Dispatch } from "react";
import { formatPts, pointsBalance } from "@/features/bets/ledger";
import type { RemainderMode } from "@/features/bets/payout";
import { PASS, type Bet } from "@/features/bets/types";
import { isValidWager } from "@/features/bets/wager";
import { makeBaseline } from "@/features/bets/info";
import { sampleSnapshot } from "@/features/bets/live-stats";
import { emitError } from "@/features/notifications/error-bus";
import type { Profile } from "@/features/settings/profile";
import type { InvitePrefs } from "@/features/social/invites";
import { settleGroups } from "@/features/social/settle-groups";
import { canProposeMode, hasPermission, type MemberPermission } from "@/features/table/hosts";
import { makeCode, subscribeCheck } from "@/features/table/quick-picks";
import { newId } from "@/lib/ids";
import { dateTimeLabel, nowLabel } from "@/lib/time";
import { canBetOn } from "./bets-reducer";
import type { Action, AppState } from "./state";
import { canChangeDebtCap, canRiskWager, isDebtCap } from "@/features/settings/debt-cap";

export interface ProposeInput {
  tableId: string;
  modeId: Bet["modeId"];
  gameId?: string | undefined;
  settledBy?: Bet["settledBy"];
  config: Bet["config"];
  question: string;
  choices: string[];
  wager: number;
  durationSec: number;
  forFun?: boolean | undefined;
  overtime?: Bet["overtime"];
}

/**
 * What the screens can ask for. Each command checks the rules the player should hear about
 * (as an error notice) and then dispatches; the reducer re-checks everything.
 * TODO(backend): an HTTP/WebSocket client implements this same interface.
 */
export interface AppCommands {
  setDebtCap: (cap: number | null) => void;
  /** Returns the new bet id, or null (with an error notice) when it can't be sent. */
  propose: (input: ProposeInput) => string | null;
  pick: (betId: string, selection: string) => void;
  /** Call a U2Pick result (you must be one of its settlers). */
  settle: (betId: string, winningChoice: string | null) => void;
  /** Sample data only: stands in for the sports feed reporting a game result. */
  simulateResult: (betId: string, winningChoice: string | null) => void;
  overrideQuickPick: (betId: string) => void;
  send: (tableId: string, text: string) => void;
  sendGif: (tableId: string, gifId: string) => void;
  react: (messageId: string, emoji: string) => void;
  updateProfile: (profile: Profile) => void;
  follow: (userId: string, on: boolean) => void;
  block: (userId: string, on: boolean) => void;
  /** Removes you from everything; the caller signs out right after. */
  deleteAccount: () => void;
  setInvitePrefs: (prefs: InvitePrefs) => void;
  respondInvite: (inviteId: string, accept: boolean) => void;
  createTable: (name: string, emoji: string, memberIds?: string[]) => string;
  updateTable: (tableId: string, name: string, emoji: string) => void;
  addMembers: (tableId: string, userIds: string[]) => void;
  removeMember: (tableId: string, userId: string) => void;
  /** Host-only; refused while any bet here is Active or Pending. */
  deleteTable: (tableId: string) => void;
  toggleHost: (tableId: string, userId: string) => void;
  setPermission: (tableId: string, userId: string, perm: MemberPermission, on: boolean) => void;
  setRemainderMode: (tableId: string, mode: RemainderMode) => void;
  setHostsOnlyBets: (tableId: string, on: boolean) => void;
  setU2PickOff: (tableId: string, off: boolean) => void;
  setQuickPicks: (tableId: string, on: boolean) => void;
  /** Starts sharing with a fresh code, or (false) stops sharing. A new code cuts off old subscribers. */
  setSharing: (tableId: string, on: boolean) => void;
  /** Returns why it failed, or null once subscribed. */
  subscribe: (tableId: string, code: string) => string | null;
  unsubscribe: (tableId: string, sourceId: string) => void;
  settlePoints: (tableId: string) => void;
  settlePerson: (otherUserId: string, step: "sent" | "paid" | "unpaid" | "forgiven" | "ask" | "decline", expectedRevision?: string) => void;
  markSent: (messageId: string, lineUserId: string) => void;
  confirmPaid: (messageId: string, lineUserId: string) => void;
  /** "Didn't get it": a payment marked sent goes back to unpaid. */
  disputePaid: (messageId: string, lineUserId: string) => void;
  forgive: (messageId: string, lineUserId: string) => void;
  askForgive: (messageId: string, lineUserId: string) => void;
  declineForgive: (messageId: string, lineUserId: string) => void;
}

/**
 * Hooks for the sample world to answer what you just did (tablemates join your bet,
 * people follow back). A real backend pushes those as events instead.
 */
export interface SampleReplies {
  proposed: (betId: string, tableId: string, choices: string[]) => void;
  chatted: (tableId: string) => void;
  followed: (userId: string) => void;
  markedSent: (messageId: string, lineUserId: string) => void;
  askedForgive: (messageId: string, lineUserId: string) => void;
  addedMembers: (tableId: string, userIds: string[]) => void;
}

export function createCommands(
  getState: () => AppState,
  dispatch: Dispatch<Action>,
  replies?: SampleReplies,
): AppCommands {
  const me = () => getState().meId;
  const at = nowLabel;

  return {
    setDebtCap: (cap) => {
      const s = getState();
      if (!canChangeDebtCap(s, s.meId)) {
        emitError("Debt cap locked", "Clear all debt before changing your debt cap, even if you are net positive.");
        return;
      }
      if (!isDebtCap(cap)) return;
      dispatch({ type: "setDebtCap", userId: s.meId, cap });
    },
    propose: (input) => {
      const s = getState();
      const t = s.tables.find((x) => x.id === input.tableId);
      if (!t || !canProposeMode(t, s.meId, input.modeId)) {
        const [title, why] =
          t && !hasPermission(t, s.meId, "bets")
            ? ["Betting is off for you", "A host turned off betting for you at this table."]
            : t?.u2pickOff && input.modeId === "u2pick"
              ? ["U2Pick is off", "A host turned off custom bets at this table."]
              : ["Bets are host-only", "Only hosts can start bets at this table."];
        emitError(title, why);
        return null;
      }
      if (!input.forFun && !isValidWager(input.wager)) {
        emitError("Wager out of range", "Bets must be between 0.10 and 10.00 pts.");
        return null;
      }
      if (!input.forFun && pointsBalance(s.meId, s.bets) < input.wager) {
        emitError(
          "Not enough points",
          `You need ${formatPts(input.wager)} pts to send this ticket.`,
        );
        return null;
      }
      const bet: Bet = {
        ...input,
        id: newId("b"),
        proposerId: s.meId,
        closesInSec: input.durationSec,
        status: "active",
        participants: [{ userId: s.meId, selection: PASS }],
        createdAt: at(),
      };
      if (!canRiskWager(s, s.meId, input.wager, input.forFun)) {
        emitError("Debt cap reached", "This wager would exceed your debt cap. Review your debt in Safety settings.");
        return null;
      }
      // TODO(backend): the server captures the baseline from the live feed.
      const g = input.gameId ? s.games.find((x) => x.id === input.gameId) : undefined;
      if (g) bet.baseline = makeBaseline(bet, sampleSnapshot(g), bet.createdAt);
      dispatch({ type: "propose", bet, at: at() });
      replies?.proposed(bet.id, bet.tableId, bet.choices);
      return bet.id;
    },
    pick: (betId, selection) => {
      const s = getState();
      const bet = s.bets.find((b) => b.id === betId);
      if (!bet || bet.status !== "active") {
        emitError("Picks are locked", "This ticket closed before your pick went through.");
        return;
      }
      if (!canBetOn(s, betId, s.meId)) {
        emitError("Betting is off for you", "A host turned off betting for you at this table.");
        return;
      }
      const current = bet.participants.find((p) => p.userId === s.meId)?.selection ?? PASS;
      const joiningPot = selection !== PASS && current === PASS;
      if (selection !== PASS && !canRiskWager(s, s.meId, bet.wager, bet.forFun)) {
        emitError("Debt cap reached", "This wager would exceed your debt cap. You can still choose no guess or play 4fun.");
        return;
      }
      if (!bet.forFun && joiningPot && pointsBalance(s.meId, s.bets) < bet.wager) {
        emitError("Not enough points", `You need ${formatPts(bet.wager)} pts to get in this pot.`);
        return;
      }
      dispatch({ type: "pick", betId, userId: s.meId, selection });
    },
    settle: (betId, winningChoice) =>
      dispatch({ type: "settle", betId, winningChoice, actorId: me(), at: at() }),
    simulateResult: (betId, winningChoice) =>
      dispatch({ type: "gameResult", betId, winningChoice, at: at() }),
    overrideQuickPick: (betId) =>
      dispatch({ type: "overrideQuickPick", betId, actorId: me(), at: at() }),
    send: (tableId, text) => {
      dispatch({ type: "chat", tableId, authorId: me(), text, at: at() });
      replies?.chatted(tableId);
    },
    sendGif: (tableId, gifId) => {
      dispatch({ type: "gif", tableId, authorId: me(), gifId, at: at() });
      replies?.chatted(tableId);
    },
    react: (messageId, emoji) => dispatch({ type: "react", messageId, emoji, userId: me() }),
    updateProfile: (profile) => dispatch({ type: "updateProfile", profile }),
    follow: (userId, on) => {
      dispatch({ type: "follow", from: me(), to: userId, on });
      if (on) replies?.followed(userId);
    },
    block: (userId, on) => dispatch({ type: "block", from: me(), to: userId, on }),
    deleteAccount: () => dispatch({ type: "deleteAccount", userId: me() }),
    setInvitePrefs: (prefs) => dispatch({ type: "setInvitePrefs", userId: me(), prefs }),
    respondInvite: (inviteId, accept) =>
      dispatch({ type: "respondInvite", inviteId, actorId: me(), accept, at: at() }),
    createTable: (name, emoji, memberIds = []) => {
      const id = newId("t");
      dispatch({
        type: "createTable",
        table: { id, name, emoji, memberIds: [me()], hostIds: [me()] },
        at: at(),
      });
      if (memberIds.length)
        dispatch({ type: "addMembers", tableId: id, userIds: memberIds, at: at() });
      return id;
    },
    updateTable: (tableId, name, emoji) =>
      dispatch({ type: "updateTable", tableId, name, emoji, at: at() }),
    addMembers: (tableId, userIds) => {
      dispatch({ type: "addMembers", tableId, userIds, at: at() });
      replies?.addedMembers(tableId, userIds);
    },
    deleteTable: (tableId) => dispatch({ type: "deleteTable", tableId, at: at() }),
    removeMember: (tableId, userId) =>
      dispatch({ type: "removeMember", tableId, userId, at: at() }),
    toggleHost: (tableId, userId) => dispatch({ type: "toggleHost", tableId, userId, at: at() }),
    setPermission: (tableId, userId, perm, on) =>
      dispatch({ type: "setPermission", tableId, userId, perm, on, at: at() }),
    setRemainderMode: (tableId, mode) =>
      dispatch({ type: "setRemainderMode", tableId, mode, at: at() }),
    setHostsOnlyBets: (tableId, on) =>
      dispatch({ type: "setHostsOnlyBets", tableId, on, at: at() }),
    setU2PickOff: (tableId, off) => dispatch({ type: "setU2PickOff", tableId, off, at: at() }),
    setQuickPicks: (tableId, on) =>
      dispatch({ type: "setQuickPicks", tableId, on, code: makeCode(), at: at() }),
    setSharing: (tableId, on) =>
      dispatch({ type: "setSharing", tableId, code: on ? makeCode() : null, at: at() }),
    subscribe: (tableId, code) => {
      const s = getState();
      const table = s.tables.find((t) => t.id === tableId);
      if (!table) return "Table not found.";
      const check = subscribeCheck(s.tables, table, s.meId, code);
      if ("error" in check) return check.error;
      dispatch({ type: "subscribe", tableId, code, at: at() });
      return null;
    },
    unsubscribe: (tableId, sourceId) =>
      dispatch({ type: "unsubscribe", tableId, sourceId, at: at() }),
    settlePoints: (tableId) => dispatch({ type: "settlePoints", tableId, at: dateTimeLabel(Date.now()) }),
    settlePerson: (otherUserId, step, expectedRevision) => {
      const s = getState();
      const group = settleGroups(s.messages, s.meId).find((g) => g.otherUserId === otherUserId);
      if (!group || (expectedRevision !== undefined && expectedRevision !== group.revision)) {
        emitError("Settle-up changed", "Review the new balance in Friends before continuing.");
        return;
      }
      dispatch({ type: "settlePerson", otherUserId, step, actorId: s.meId, now: Date.now(), expectedRevision: group.revision });
    },
    markSent: (messageId, lineUserId) => {
      dispatch({ type: "payment", messageId, lineUserId, status: "sent", actorId: me() });
      replies?.markedSent(messageId, lineUserId);
    },
    confirmPaid: (messageId, lineUserId) =>
      dispatch({ type: "payment", messageId, lineUserId, status: "paid", actorId: me() }),
    disputePaid: (messageId, lineUserId) =>
      dispatch({ type: "payment", messageId, lineUserId, status: "unpaid", actorId: me() }),
    forgive: (messageId, lineUserId) =>
      dispatch({ type: "payment", messageId, lineUserId, status: "forgiven", actorId: me() }),
    askForgive: (messageId, lineUserId) => {
      dispatch({
        type: "forgiveAsk",
        messageId,
        lineUserId,
        answer: "ask",
        actorId: me(),
        now: Date.now(),
      });
      replies?.askedForgive(messageId, lineUserId);
    },
    declineForgive: (messageId, lineUserId) =>
      dispatch({
        type: "forgiveAsk",
        messageId,
        lineUserId,
        answer: "decline",
        actorId: me(),
        now: Date.now(),
      }),
  };
}

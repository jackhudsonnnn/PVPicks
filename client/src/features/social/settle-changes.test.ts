import { describe, expect, it, vi } from "vitest";
import { needsSettleReview, reconcileSettleChanges, requiresSettleReview, settlePairKey } from "./settle-changes";
import { settleGroups } from "./settle-groups";
import { reducer } from "@/features/store/reducer";
import type { AppState } from "@/features/store/state";
import type { SettlementMessage } from "@/features/table/settlements";
import { createCommands } from "@/features/store/commands";
import { onError } from "@/lib/error-bus";

const line = (id: string, amount: number): SettlementMessage => ({ id, kind: "settlement", tableId: id, hostId: "a", lines: [{ userId: "b", amount: -amount }], hostNet: amount, at: "now" });
const sent = { ...line("one", 5), payments: { b: "sent" as const } };

describe("changed settle-up balances", () => {
  it("requires opening the warning before sending or asking for forgiveness", () => {
    const change = { fromId: "a", toId: "b", before: 6, after: -6, revision: "one" };
    expect(needsSettleReview(change, undefined)).toBe(true);
    expect(needsSettleReview(change, change)).toBe(false);
    expect(needsSettleReview(undefined, undefined)).toBe(false);
  });
  it("requires a fresh review for another warning even with the same ledger revision", () => {
    const reviewed = { fromId: "a", toId: "b", before: 6, after: -6, revision: "one" };
    expect(needsSettleReview({ ...reviewed, before: 8 }, reviewed)).toBe(true);
  });
  it.each([[3, 8], [-3, 2], [-9, -4], [-5, 0]])("resets a sent $5 claim after adding %s, leaving net %s", (delta, expected) => {
    const after = [sent, line("two", delta)];
    const result = reconcileSettleChanges([sent], after);
    const group = settleGroups(result.messages, "a")[0];
    expect(group?.net).toBe(expected);
    expect(group?.payments.every((p) => p.status === "unpaid")).toBe(true);
    expect(result.changes[settlePairKey("a", "b")]).toMatchObject({ before: 5, after: expected });
    expect(result.messages[0]).toMatchObject({ lines: sent.lines, hostNet: 5 });
  });
  it("cancels an open forgiveness request on a balance change", () => {
    const asked = { ...line("one", 5), forgiveAsks: { b: { status: "open" as const, at: 100 } } };
    const result = reconcileSettleChanges([asked], [asked, line("two", 3)]);
    expect(settleGroups(result.messages, "b")[0]?.payment.ask).toBeUndefined();
    expect(result.changes[settlePairKey("a", "b")]?.after).toBe(8);
  });
  it("preserves pending claims when the net is unchanged", () => {
    const result = reconcileSettleChanges([sent], [sent, line("two", 3), line("three", -3)]);
    expect(settleGroups(result.messages, "a")[0]?.payment.status).toBe("sent");
    expect(result.changes).toEqual({});
  });
  it("never resets paid or forgiven history", () => {
    const paid = { ...line("paid", 2), payments: { b: "paid" as const } };
    const forgiven = { ...line("forgiven", 1), payments: { b: "forgiven" as const } };
    const result = reconcileSettleChanges([sent, paid, forgiven], [sent, paid, forgiven, line("two", 3)]);
    expect(result.messages.slice(1, 3)).toEqual([paid, forgiven]);
  });
  it("does not report successful full-net payment as cancellation", () => {
    expect(reconcileSettleChanges([sent], [{ ...sent, payments: { b: "paid" } }]).changes).toEqual({});
  });
  it("rejects a stale full-net confirmation without clearing any lines", () => {
    const revision = settleGroups([sent], "a")[0]?.revision;
    if (revision === undefined) throw new Error("Missing group revision");
    const state: AppState = { meId: "a", messages: [sent, line("two", 3)], bets: [], games: [], tables: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test" };
    expect(reducer(state, { type: "settlePerson", otherUserId: "b", actorId: "a", step: "paid", now: 100, expectedRevision: revision })).toBe(state);
  });
  it("preserves unrelated in-flight receipts when an individual receipt is confirmed", () => {
    const second = { ...line("two", 3), payments: { b: "sent" as const } };
    const state: AppState = { meId: "a", messages: [sent, second], bets: [], games: [], tables: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test" };
    const next = reducer(state, { type: "payment", messageId: "one", lineUserId: "b", actorId: "a", status: "paid" });
    expect(settleGroups(next.messages, "a")[0]?.payment).toMatchObject({ amount: 3, status: "sent" });
    expect(next.settleChanges?.[settlePairKey("a", "b")]).toBeUndefined();
  });
  it("blocks both individual and grouped payer actions until that payer reviews", () => {
    const change = { fromId: "a", toId: "b", before: 5, after: 8, revision: "changed" };
    const state: AppState = { meId: "b", messages: [line("one", 5)], bets: [], games: [], tables: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test", settleChanges: { [settlePairKey("a", "b")]: change } };
    expect(reducer(state, { type: "payment", messageId: "one", lineUserId: "b", actorId: "b", status: "sent" })).toBe(state);
    expect(reducer(state, { type: "forgiveAsk", messageId: "one", lineUserId: "b", actorId: "b", answer: "ask", now: 100 })).toBe(state);
    for (const step of ["sent", "ask"] as const) {
      expect(reducer(state, { type: "settlePerson", actorId: "b", otherUserId: "a", step, now: 100 })).toBe(state);
    }
    const reviewed = reducer(state, { type: "reviewSettleChange", actorId: "b", otherUserId: "a", change });
    expect(requiresSettleReview(reviewed, "b", "a")).toBe(false);
    expect(requiresSettleReview(reviewed, "a", "b")).toBe(true);
    expect(reducer(reviewed, { type: "payment", messageId: "one", lineUserId: "b", actorId: "b", status: "sent" })).not.toBe(reviewed);
    expect(reducer(reviewed, { type: "settlePerson", actorId: "b", otherUserId: "a", step: "ask", now: 100 }).settleChanges?.[settlePairKey("a", "b")]).toBe(change);
  });
  it("refreshes only pending reviews on a partial closure and retains them after full closure", () => {
    const change = { fromId: "a", toId: "b", before: 3, after: 8, revision: "changed" };
    const state: AppState = { meId: "a", messages: [line("one", 5), line("two", 3)], bets: [], games: [], tables: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test", settleChanges: { [settlePairKey("a", "b")]: change } };
    const reviewed = reducer(state, { type: "reviewSettleChange", actorId: "a", otherUserId: "b", change });
    const partial = reducer(reviewed, { type: "payment", messageId: "one", lineUserId: "b", actorId: "a", status: "paid" });
    expect(requiresSettleReview(partial, "a", "b")).toBe(false);
    expect(requiresSettleReview(partial, "b", "a")).toBe(true);
    expect(reducer({ ...partial, meId: "b" }, { type: "reviewSettleChange", actorId: "b", otherUserId: "a", change }).settleReviews).toEqual(partial.settleReviews);
    const closed = reducer(partial, { type: "payment", messageId: "two", lineUserId: "b", actorId: "a", status: "paid" });
    expect(requiresSettleReview(closed, "b", "a")).toBe(true);
    expect(closed.settleChanges?.[settlePairKey("a", "b")]).toBe(partial.settleChanges?.[settlePairKey("a", "b")]);
  });
  it("reports blocked commands without dispatching or scheduling mock replies", () => {
    const change = { fromId: "a", toId: "b", before: 2, after: 5, revision: "changed" };
    const state: AppState = { meId: "b", messages: [line("one", 5)], bets: [], games: [], tables: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test", settleChanges: { [settlePairKey("a", "b")]: change } };
    const dispatch = vi.fn();
    const reply = vi.fn();
    const errors = vi.fn();
    const unsubscribe = onError(errors);
    try {
      const commands = createCommands(() => state, dispatch, {
        proposed: reply, chatted: reply, followed: reply, markedSent: reply, askedForgive: reply, addedMembers: reply,
      });
      commands.markSent("one", "b");
      commands.askForgive("one", "b");
      commands.settlePerson("a", "sent");
      commands.settlePerson("a", "ask");
      expect(dispatch).not.toHaveBeenCalled();
      expect(reply).not.toHaveBeenCalled();
      expect(errors).toHaveBeenCalledTimes(4);
    } finally {
      unsubscribe();
    }
  });
});
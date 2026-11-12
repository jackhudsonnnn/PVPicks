import { describe, expect, it } from "vitest";
import { needsSettleReview, reconcileSettleChanges, settlePairKey } from "./settle-changes";
import { settleGroups } from "./settle-groups";
import { reducer } from "@/features/store/reducer";
import type { AppState } from "@/features/store/state";
import type { SettlementMessage } from "@/features/table/settlements";

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
});
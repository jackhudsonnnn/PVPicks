import { describe, expect, it } from "vitest";
import { receiptNet, settleReceipts } from "./settle-receipts";
import { settleGroups } from "./settle-groups";
import { reducer } from "@/features/store/reducer";
import type { AppState } from "@/features/store/state";
import type { SettlementMessage } from "@/features/table/settlements";

const row = (id: string, amount: number): SettlementMessage => ({ id, kind: "settlement", tableId: "same", hostId: "peer", lines: [{ userId: "me", amount }], hostNet: -amount, at: "Nov 11, 11:30 PM" });
const world = (messages: SettlementMessage[]): AppState => ({ meId: "me", messages, bets: [], tables: [], games: [], users: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, blocks: {}, seq: 0, idBase: "test" });
describe("individual settle-up receipts", () => {
  it("keeps repeated settlements at one table as separate dated rows", () => {
    const rows = settleReceipts([row("a", -30), row("b", 20)], "me", "peer");
    expect(rows.map((p) => p.messageId)).toEqual(["b", "a"]);
    expect(rows.every((p) => p.at === "Nov 11, 11:30 PM")).toBe(true);
    expect(receiptNet(rows, "me")).toBe(-10);
  });
  it("sent $30 stays outstanding until received; confirming flips -$10 to +$20", () => {
    const state = world([row("a", -30), row("b", 20)]);
    const sent = reducer(state, { type: "payment", messageId: "a", lineUserId: "me", actorId: "me", status: "sent" });
    expect(receiptNet(settleReceipts(sent.messages, "me", "peer"), "me")).toBe(-10);
    const confirmed = reducer(sent, { type: "payment", messageId: "a", lineUserId: "me", actorId: "peer", status: "paid" });
    expect(receiptNet(settleReceipts(confirmed.messages, "me", "peer"), "me")).toBe(20);
    expect(settleReceipts(confirmed.messages, "me", "peer").at(-1)?.status).toBe("paid");
  });
  it("cannot sign the other person's sent or received column", () => {
    const state = world([row("a", -30)]);
    expect(reducer(state, { type: "payment", messageId: "a", lineUserId: "me", actorId: "peer", status: "sent" })).toBe(state);
    expect(reducer(state, { type: "payment", messageId: "a", lineUserId: "me", actorId: "me", status: "paid" })).toBe(state);
  });
  it("retains access to completed history without including it in the net", () => {
    const paid = { ...row("a", -30), payments: { me: "paid" as const } };
    expect(settleGroups([paid], "me")).toEqual([]);
    expect(settleGroups([paid], "me", true)[0]).toMatchObject({ net: 0, payments: [] });
    expect(settleReceipts([paid], "me", "peer")).toHaveLength(1);
  });
  it("not received resets only that row and forgiveness clears only that amount", () => {
    const state = world([{ ...row("a", -30), payments: { me: "sent" } }, row("b", 20)]);
    const disputed = reducer(state, { type: "payment", messageId: "a", lineUserId: "me", actorId: "peer", status: "unpaid" });
    expect(receiptNet(settleReceipts(disputed.messages, "me", "peer"), "me")).toBe(-10);
    const forgiven = reducer(disputed, { type: "payment", messageId: "a", lineUserId: "me", actorId: "peer", status: "forgiven" });
    expect(receiptNet(settleReceipts(forgiven.messages, "me", "peer"), "me")).toBe(20);
  });
});
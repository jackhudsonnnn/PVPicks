import { describe, expect, it } from "vitest";
import { settleGroups } from "./settle-groups";
import { paymentsReducer } from "@/features/store/payments-reducer";
import type { AppState } from "@/features/store/state";
import type { SettlementMessage } from "@/features/table/settlements";

const line = (id: string, tableId: string, amount: number): SettlementMessage => ({
  id, tableId, kind: "settlement", hostId: "me", lines: [{ userId: "peer", amount }], hostNet: -amount, at: "now",
});
const messages = [line("a", "one", -15), line("b", "two", 5)];
const world = (rows = messages): AppState => ({
  meId: "me", messages: rows, bets: [], tables: [], games: [], users: [], follows: {}, invitePrefs: {},
  tableInvites: [], reactions: {}, blocks: {}, idBase: "test", seq: 0,
});
const step = (state: AppState, actorId: string, step: "sent" | "paid" | "forgiven" | "ask" | "unpaid") =>
  paymentsReducer(state, { type: "settlePerson", actorId, otherUserId: actorId === "me" ? "peer" : "me", step, now: 100 });

describe("person settle-ups", () => {
  it("groups tables into one net with a signed per-table breakdown", () => {
    const groups = settleGroups(messages, "me");
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ otherUserId: "peer", net: 10, tables: [{ tableId: "one", net: 15 }, { tableId: "two", net: -5 }] });
    expect(settleGroups(messages, "peer")[0]?.net).toBe(-10);
  });
  it("nets repeated settlements at the same table in cents", () => {
    expect(settleGroups([line("a", "one", -.1), line("b", "one", -.2)], "me")[0]).toMatchObject({ net: .3, tables: [{ net: .3 }] });
  });
  it("a sent payment in the opposite direction cannot block paying the new net", () => {
    const rows = [{ ...line("a", "one", -6), payments: { peer: "sent" as const } }, line("b", "two", 12)];
    expect(settleGroups(rows, "me")[0]?.payment).toMatchObject({ from: "me", amount: 6, status: "unpaid" });
    expect(step(world(rows), "me", "sent").messages).toMatchObject([{ payments: { peer: "sent" } }, { payments: { peer: "sent" } }]);
  });
  it("confirming the full net closes all lines including opposite directions", () => {
    const next = step(world(), "me", "paid");
    expect(settleGroups(next.messages, "me")).toEqual([]);
    expect(next.messages).toMatchObject([{ payments: { peer: "paid" } }, { payments: { peer: "paid" } }]);
  });
  it("only the net payer can mark the entire amount sent", () => {
    const state = world();
    expect(step(state, "me", "sent")).toBe(state);
    const sent = step(state, "peer", "sent");
    expect(sent.messages).toMatchObject([{ payments: { peer: "sent" } }, { payments: { peer: "sent" } }]);
    expect(step(sent, "me", "forgiven")).toBe(sent);
    expect(settleGroups(step(sent, "me", "unpaid").messages, "me")[0]?.payment.status).toBe("unpaid");
  });
  it("forgiveness clears the full net, never a single contributing amount", () => {
    const state = world();
    expect(step(state, "peer", "forgiven")).toBe(state);
    expect(settleGroups(step(state, "me", "forgiven").messages, "me")).toEqual([]);
  });
  it("applies one forgiveness request to all contributing lines", () => {
    const asked = step(world(), "peer", "ask");
    expect(asked.messages).toMatchObject([{ forgiveAsks: { peer: { status: "open", at: 100 } } }, { forgiveAsks: { peer: { status: "open", at: 100 } } }]);
    expect(step(asked, "peer", "ask")).toBe(asked);
  });
  it("a zero-net group can clear without any payment", () => {
    const state = world([line("a", "one", -5), line("b", "two", 5)]);
    expect(settleGroups(state.messages, "me")[0]?.net).toBe(0);
    expect(settleGroups(step(state, "peer", "paid").messages, "me")).toEqual([]);
  });
});
import { describe, expect, it } from "vitest";
import type { AppState } from "@/features/store/state";
import type { Bet } from "@/features/bets/types";
import { PASS } from "@/features/bets/types";
import { reducer } from "@/features/store/reducer";
import { canChangeDebtCap, canRiskWager, DEFAULT_DEBT_CAP, isDebtCap, userDebt } from "./debt-cap";

const bet = (over: Partial<Bet> = {}): Bet => ({
  id: "b", tableId: "t", proposerId: "me", modeId: "either_or", config: {},
  question: "Who?", choices: ["A", "B"], wager: 1, durationSec: 1, closesInSec: 1,
  status: "active", participants: [{ userId: "me", selection: "A" }], createdAt: "now", ...over,
});
const world = (over: Partial<AppState> = {}): AppState => ({
  meId: "me", bets: [], messages: [], tables: [
    { id: "t", name: "One", emoji: "", memberIds: ["me", "a"], hostIds: ["me"] },
    { id: "t2", name: "Two", emoji: "", memberIds: ["me", "a"], hostIds: ["me"] },
  ], games: [], follows: {}, invitePrefs: {}, tableInvites: [], reactions: {}, users: [],
  blocks: {}, idBase: "test", seq: 0, ...over,
});

describe("Debt Cap", () => {
  it("is off by default", () => {
    expect(DEFAULT_DEBT_CAP).toBeNull();
    expect(canRiskWager(world(), "me", 10000)).toBe(true);
  });
  it("adds negative table True balances without offsetting positive tables", () => {
    const state = world({ bets: [bet({ status: "pending", wager: 2.5 }), bet({ id: "win", tableId: "t2", status: "resolved", deltas: { me: 100 } })] });
    expect(userDebt(state, "me")).toBe(2.5);
    expect(canChangeDebtCap(state, "me")).toBe(false);
  });
  it("adds negative person settle-up nets without offsetting money owed by other people", () => {
    const state = world({ messages: [
      { id: "s", tableId: "t", kind: "settlement", hostId: "a", hostNet: 3.25, at: "now", lines: [{ userId: "me", amount: -3.25 }] },
      { id: "s2", tableId: "t2", kind: "settlement", hostId: "b", hostNet: -100, at: "now", lines: [{ userId: "me", amount: 100 }] },
    ] });
    expect(userDebt(state, "me")).toBe(3.25);
    expect(canChangeDebtCap(state, "me")).toBe(false);
  });
  it("blocks every limit change with any debt, even if net positive", () => {
    const state = world({ debtCaps: { me: 25 }, bets: [
      bet({ status: "resolved", deltas: { me: -0.01 } }),
      bet({ id: "win", tableId: "t2", status: "resolved", deltas: { me: 100 } }),
    ] });
    for (const cap of [null, 0, 10, 50]) expect(reducer(state, { type: "setDebtCap", userId: "me", cap })).toBe(state);
  });
  it("permits whole-point edits and disabling only at zero debt", () => {
    const state = world();
    expect(reducer(state, { type: "setDebtCap", userId: "me", cap: 25 }).debtCaps?.["me"]).toBe(25);
    expect(reducer(state, { type: "setDebtCap", userId: "me", cap: null }).debtCaps?.["me"]).toBeNull();
    expect(isDebtCap(1.5)).toBe(false);
    expect(isDebtCap(-1)).toBe(false);
  });
  it("accepts exact-cap wagers and exempts 4fun", () => {
    const state = world({ debtCaps: { me: 2 }, bets: [bet({ status: "pending" })] });
    expect(canRiskWager(state, "me", 1)).toBe(true);
    expect(canRiskWager(state, "me", 1.01)).toBe(false);
    expect(canRiskWager(state, "me", 10, true)).toBe(true);
  });
  it("enforces caps on proposals, joins and picks", () => {
    const state = world({ debtCaps: { me: 0 }, bets: [bet({ participants: [] })] });
    expect(reducer(state, { type: "pick", betId: "b", userId: "me", selection: "A" })).toBe(state);
    expect(reducer(state, { type: "join", betId: "b", userId: "me", selection: "A" })).toBe(state);
    expect(reducer(state, { type: "propose", bet: bet({ id: "new" }), at: "now" })).toBe(state);
    expect(reducer(state, { type: "join", betId: "b", userId: "me", selection: PASS }).bets[0]?.participants[0]?.selection).toBe(PASS);
  });
  it("locks simultaneous bets sequentially and passes picks over the cap", () => {
    const state = world({ debtCaps: { me: 1 }, bets: [bet(), bet({ id: "second" })] });
    const next = reducer(state, { type: "tick", at: "later" });
    expect(next.bets[0]?.participants[0]?.selection).toBe("A");
    expect(next.bets[1]?.participants[0]?.selection).toBe(PASS);
    expect(userDebt(next, "me")).toBe(1);
  });
  it("saved limits reload even while debt exists", () => {
    const state = world({ bets: [bet({ status: "pending" })] });
    expect(reducer(state, { type: "loadDebtCap", cap: 25 }).debtCaps?.["me"]).toBe(25);
  });
});
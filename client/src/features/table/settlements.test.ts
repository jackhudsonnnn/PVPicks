import { describe, expect, it } from "vitest";
import type { Bet } from "@/features/bets/types";
import {
  ASK_AGAIN_MS,
  canAskForgive,
  canDecline,
  canDispute,
  canForgive,
  netSettleUps,
  setForgiveAsk,
  canConfirm,
  canMarkSent,
  outstandingFor,
  paymentsOf,
  setPaymentStatus,
  unconfirmedTotal,
  type SettlementMessage,
} from "./settlements";

const m: SettlementMessage = {
  id: "m1",
  tableId: "t",
  kind: "settlement",
  hostId: "host",
  lines: [
    { userId: "a", amount: -5 },
    { userId: "b", amount: 10 },
  ],
  hostNet: -5,
  at: "now",
};

describe("settlement payments", () => {
  it("a negative line means the member pays the host", () => {
    const [a, b] = paymentsOf(m);
    expect(a).toMatchObject({ from: "a", to: "host", amount: 5, status: "unpaid" });
    expect(b).toMatchObject({ from: "host", to: "b", amount: 10 });
  });

  it("only the payer can mark sent; only the receiver can confirm", () => {
    const [a] = paymentsOf(m);
    expect(canMarkSent(a!, "a")).toBe(true);
    expect(canMarkSent(a!, "host")).toBe(false);
    expect(canConfirm(a!, "host")).toBe(true);
    expect(canConfirm(a!, "a")).toBe(false);
  });

  it("confirmed payments drop off the outstanding list", () => {
    expect(outstandingFor([m], "host")).toHaveLength(2);
    const paid = setPaymentStatus(m, "a", "paid");
    expect(outstandingFor([paid], "host").map((p) => p.lineUserId)).toEqual(["b"]);
    expect(outstandingFor([paid], "a")).toEqual([]);
  });
});

describe("unconfirmed total (top-bar points)", () => {
  const bet = (over: Partial<Bet>): Bet => ({
    id: "b",
    tableId: "t",
    proposerId: "a",
    modeId: "either_or",
    config: {},
    question: "?",
    choices: ["x", "y"],
    wager: 5,
    durationSec: 60,
    closesInSec: 0,
    status: "resolved",
    participants: [],
    deltas: { a: 5, host: -5 },
    createdAt: "now",
    ...over,
  });

  it("adds unsettled resolved results and unpaid settlement payments", () => {
    // a owes host 5 from the settlement, and won 5 on a newer bet.
    expect(unconfirmedTotal("a", [bet({})], [m])).toBe(0);
    expect(unconfirmedTotal("b", [], [m])).toBe(10);
    expect(unconfirmedTotal("host", [], [m])).toBe(-5);
  });

  it("ignores settled bets and confirmed payments", () => {
    const paid = setPaymentStatus(m, "a", "paid");
    expect(unconfirmedTotal("a", [bet({ settled: true })], [paid])).toBe(0);
    expect(unconfirmedTotal("host", [], [paid])).toBe(-10);
  });

  it("a payment marked sent still counts until it's confirmed", () => {
    expect(unconfirmedTotal("a", [], [setPaymentStatus(m, "a", "sent")])).toBe(-5);
  });
});

describe("forgiving settle-ups", () => {
  const [owe] = paymentsOf(m); // a owes host 5
  const NOW = 1_000_000_000;

  it("only the person owed can forgive, and only before it's marked sent", () => {
    expect(canForgive(owe!, "host")).toBe(true);
    expect(canForgive(owe!, "a")).toBe(false);
    const [sent] = paymentsOf(setPaymentStatus(m, "a", "sent"));
    expect(canForgive(sent!, "host")).toBe(false);
  });

  it("the payer can ask once; after a decline, again only after a day", () => {
    expect(canAskForgive(owe!, "a", NOW)).toBe(true);
    expect(canAskForgive(owe!, "host", NOW)).toBe(false);
    const [asked] = paymentsOf(setForgiveAsk(m, "a", { status: "open", at: NOW }));
    expect(canAskForgive(asked!, "a", NOW + ASK_AGAIN_MS * 5)).toBe(false);
    expect(canDecline(asked!, "host")).toBe(true);
    const [declined] = paymentsOf(setForgiveAsk(m, "a", { status: "declined", at: NOW }));
    expect(canAskForgive(declined!, "a", NOW + ASK_AGAIN_MS - 1)).toBe(false);
    expect(canAskForgive(declined!, "a", NOW + ASK_AGAIN_MS)).toBe(true);
  });

  it("forgiven settle-ups clear from the points number", () => {
    expect(netSettleUps([m], "a")).toBe(-5);
    expect(netSettleUps([m], "host")).toBe(-5); // +5 from a, -10 to b
    const forgiven = setPaymentStatus(m, "a", "forgiven");
    expect(netSettleUps([forgiven], "a")).toBe(0);
    expect(outstandingFor([forgiven], "a")).toEqual([]);
  });

  it("the points number ignores results a host hasn't settled yet", () => {
    expect(netSettleUps([], "a")).toBe(0);
  });

  it("a payment marked sent can go back to unpaid if it never arrived", () => {
    const [sent] = paymentsOf(setPaymentStatus(m, "a", "sent"));
    expect(canDispute(sent!, "host")).toBe(true);
    expect(canDispute(sent!, "a")).toBe(false);
    expect(canDispute(owe!, "host")).toBe(false);
  });
});

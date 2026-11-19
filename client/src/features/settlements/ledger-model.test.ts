import { describe, expect, it } from "vitest";
import type { Settle, SettlementGroup, SettlementLine } from "./api";
import { cardTone, hoursLeft, selectionActions, selectionNet, sortGroups } from "./ledger-model";

const me = "123e4567-e89b-42d3-a456-426614174000";
const them = "123e4567-e89b-42d3-a456-426614174001";
let n = 0;
const line = (payer: string, payee: string, cents: bigint): SettlementLine => ({
  id: `123e4567-e89b-42d3-a456-4266141741${String(n++).padStart(2, "0")}`,
  settlementId: "123e4567-e89b-42d3-a456-426614174090",
  tableId: "123e4567-e89b-42d3-a456-426614174091",
  payerId: payer,
  payeeId: payee,
  amountCents: cents,
  status: "unpaid",
  revision: 0,
  settleId: null,
  createdAt: "2026-11-16T12:00:00Z",
  updatedAt: "2026-11-16T12:00:00Z",
});
const settle = (canReject: boolean): Settle => ({
  id: "123e4567-e89b-42d3-a456-426614174092",
  kind: "sent",
  senderId: them,
  otherId: me,
  payerId: them,
  payeeId: me,
  amountCents: 100n,
  createdAt: "2026-11-16T12:00:00Z",
  rejectBy: "2026-11-18T12:00:00Z",
  rejectedAt: null,
  canReject,
  lines: [],
});
const group = (net: bigint, extra: Partial<SettlementGroup> = {}): SettlementGroup => ({
  otherUserId: them,
  netCents: net,
  lines: [],
  pendingReviewRevision: null,
  pendingSettles: [],
  ...extra,
});

describe("settle-up cards", () => {
  it("are yellow for a review or a send you can reject, else red, green or neutral", () => {
    expect(cardTone(group(-100n))).toBe("owe");
    expect(cardTone(group(100n))).toBe("owed");
    expect(cardTone(group(0n))).toBe("even");
    expect(cardTone(group(-100n, { pendingReviewRevision: them }))).toBe("attention");
    expect(cardTone(group(0n, { pendingSettles: [settle(true)] }))).toBe("attention");
    expect(cardTone(group(0n, { pendingSettles: [settle(false)] })), "your own send").toBe("even");
  });

  it("sort yellow, red, green, neutral, keeping the server's order within each", () => {
    const owedA = group(5n, { otherUserId: "a" });
    const even = group(0n, { otherUserId: "e" });
    const owe = group(-5n, { otherUserId: "o" });
    const owedB = group(9n, { otherUserId: "b" });
    const review = group(9n, { otherUserId: "r", pendingReviewRevision: them });
    expect(sortGroups([owedA, even, owe, owedB, review]).map((g) => g.otherUserId)).toEqual([
      "r",
      "o",
      "a",
      "b",
      "e",
    ]);
  });
});

describe("a selection", () => {
  it("is settled by the person down and marked settled by the person up or when even", () => {
    const iOwe = line(me, them, 500n);
    const theyOwe = line(them, me, 300n);
    expect(selectionNet([iOwe, theyOwe], me)).toBe(-200n);
    expect(selectionActions([iOwe, theyOwe], me)).toEqual(["sent"]);
    expect(selectionActions([iOwe, theyOwe], them)).toEqual(["received"]);
    expect(selectionActions([theyOwe], me)).toEqual(["received"]);
    expect(selectionActions([line(me, them, 300n), line(them, me, 300n)], me)).toEqual([
      "received",
    ]);
    expect(selectionActions([], me)).toEqual([]);
  });

  it("counts reject hours up", () => {
    expect(hoursLeft("2026-11-18T12:00:00Z", Date.parse("2026-11-17T12:30:00Z"))).toBe(24);
    expect(hoursLeft("2026-11-18T12:00:00Z", Date.parse("2026-11-19T00:00:00Z"))).toBe(0);
  });
});

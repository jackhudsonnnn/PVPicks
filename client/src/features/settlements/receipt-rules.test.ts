import { describe, expect, it } from "vitest";
import type { SettlementGroup, SettlementLine } from "./api";
import { groupOptions, groupSummary, isClosed, lineOptions, needsReview } from "./receipt-rules";

const me = "123e4567-e89b-42d3-a456-426614174000";
const other = "123e4567-e89b-42d3-a456-426614174001";
const line = (fields: Partial<SettlementLine> = {}): SettlementLine => ({
  id: "123e4567-e89b-42d3-a456-426614174010",
  settlementId: "123e4567-e89b-42d3-a456-426614174011",
  tableId: "123e4567-e89b-42d3-a456-426614174012",
  payerId: me,
  payeeId: other,
  amountCents: 125n,
  status: "unpaid",
  revision: 0,
  createdAt: "2026-11-14T12:00:00Z",
  updatedAt: "2026-11-14T12:00:00Z",
  ...fields,
});
const group = (lines: SettlementLine[], netCents: bigint): SettlementGroup => ({
  otherUserId: other,
  netCents,
  revision: "r1",
  lines,
  pendingReviewRevision: null,
});
const labels = (options: { label: string }[]) => options.map((option) => option.label);

describe("receipt rules", () => {
  it("summarizes open receipts by table and flags payments awaiting confirmation", () => {
    const second = "123e4567-e89b-42d3-a456-426614174013";
    expect(
      groupSummary([line(), line({ tableId: second, status: "sent" }), line({ status: "paid" })]),
    ).toEqual({ tables: 2, open: 2, awaitingReceipt: true });
    expect(isClosed(line({ status: "forgiven" }))).toBe(true);
  });

  it("offers the payer sending or asking, and the payee confirming or forgiving, for a group", () => {
    expect(labels(groupOptions(group([line()], -125n), me))).toEqual([
      "Mark group sent",
      "Ask to forgive group",
    ]);
    const owedToMe = line({ payerId: other, payeeId: me });
    expect(labels(groupOptions(group([owedToMe], 125n), me))).toEqual([
      "Confirm group received",
      "Forgive group",
    ]);
    expect(labels(groupOptions(group([line(), owedToMe], 0n), me))).toEqual(["Confirm zero net"]);
    expect(groupOptions(group([line({ status: "paid" })], 0n), me)).toEqual([]);
  });

  it("waits a day after a declined forgiveness before asking again", () => {
    const now = Date.parse("2026-11-14T13:00:00Z");
    const declined = line({ forgiveDeclinedAt: "2026-11-14T12:30:00Z" });
    expect(labels(lineOptions(declined, me, now))).toEqual(["I sent it"]);
    expect(labels(lineOptions(declined, me, now + 86_400_000))).toEqual([
      "I sent it",
      "Ask to forgive",
    ]);
  });

  it("lets the payee confirm, forgive, decline forgiveness or dispute a sent payment", () => {
    expect(labels(lineOptions(line({ status: "forgive_asked" }), other))).toEqual([
      "I received it",
      "Forgive",
      "Decline forgiveness",
    ]);
    expect(labels(lineOptions(line({ status: "sent" }), other))).toEqual([
      "I received it",
      "Didn't receive it",
    ]);
    expect(lineOptions(line({ status: "paid" }), other)).toEqual([]);
  });

  it("gates only sending and asking behind a review", () => {
    expect(
      ["sent", "ask", "ask-forgive", "paid", "confirm"].map((a) => needsReview(a as "sent")),
    ).toEqual([true, true, true, false, false]);
  });
});

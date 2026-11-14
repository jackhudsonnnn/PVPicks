import { describe, expect, it } from "vitest";
import type { SettlementLine } from "./api";
import { groupSummary, isClosed } from "./receipt-rules";

const line = (fields: Partial<SettlementLine> = {}): SettlementLine => ({
  id: "123e4567-e89b-42d3-a456-426614174010",
  settlementId: "123e4567-e89b-42d3-a456-426614174011",
  tableId: "123e4567-e89b-42d3-a456-426614174012",
  payerId: "123e4567-e89b-42d3-a456-426614174000",
  payeeId: "123e4567-e89b-42d3-a456-426614174001",
  amountCents: 125n,
  status: "unpaid",
  revision: 0,
  createdAt: "2026-11-14T12:00:00Z",
  updatedAt: "2026-11-14T12:00:00Z",
  actions: [],
  ...fields,
});

describe("receipt summaries", () => {
  it("counts open receipts by table and flags payments awaiting confirmation", () => {
    const second = "123e4567-e89b-42d3-a456-426614174013";
    expect(
      groupSummary([line(), line({ tableId: second, status: "sent" }), line({ status: "paid" })]),
    ).toEqual({ tables: 2, open: 2, awaitingReceipt: true });
    expect(isClosed(line({ status: "forgiven" }))).toBe(true);
    expect(isClosed(line({ status: "disputed" }))).toBe(false);
  });
});

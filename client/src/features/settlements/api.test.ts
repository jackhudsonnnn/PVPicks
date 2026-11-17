import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import {
  acknowledgeReview,
  createSettle,
  listSettlementGroups,
  listSettles,
  receiptHistory,
  rejectSettle,
} from "./api";

const actor = "123e4567-e89b-42d3-a456-426614174000";
const other = "123e4567-e89b-42d3-a456-426614174001";
const lineId = "123e4567-e89b-42d3-a456-426614174002";
const revision = "123e4567-e89b-42d3-a456-426614174003";
const settleId = "123e4567-e89b-42d3-a456-426614174004";
const line = {
  id: lineId,
  settlementId: revision,
  tableId: revision,
  payerId: actor,
  payeeId: other,
  amountCents: 100,
  status: "unpaid",
  revision: 1,
  settleId: null,
  createdAt: "2026-11-11T12:00:00Z",
  updatedAt: "2026-11-11T12:00:00Z",
};
const settle = {
  id: settleId,
  kind: "sent",
  senderId: actor,
  otherId: other,
  payerId: actor,
  payeeId: other,
  amountCents: 100,
  createdAt: "2026-11-16T12:00:00Z",
  rejectBy: "2026-11-18T12:00:00Z",
  rejectedAt: null,
  canReject: false,
  lines: [
    {
      id: lineId,
      tableId: revision,
      payerId: actor,
      payeeId: other,
      amountCents: 100,
      createdAt: "2026-11-11T12:00:00Z",
    },
  ],
};
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });

afterEach(() => vi.unstubAllGlobals());

describe("settlement API", () => {
  it("preserves int64 cents exactly and rejects receipts or settles for other parties", async () => {
    const hugeLine = JSON.stringify(line).replace(
      '"amountCents":100',
      '"amountCents":9223372036854775807',
    );
    const groups = `[{"otherUserId":"${other}","netCents":-9223372036854775808,"pendingReviewRevision":null,"pendingSettles":[],"lines":[${hugeLine}]}]`;
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(groups))
      .mockResolvedValueOnce(json([{ ...line, payerId: other, payeeId: revision }]))
      .mockResolvedValueOnce(
        json({ items: [{ ...settle, senderId: revision }], nextCursor: null }),
      );
    vi.stubGlobal("fetch", fetch);
    const [group] = await listSettlementGroups(actor);
    expect(group?.netCents).toBe(-9223372036854775808n);
    expect(group?.lines[0]?.amountCents).toBe(9223372036854775807n);
    expect(fetch.mock.calls[0]?.[0]).toBe("/api/people/settle-groups");
    await expect(receiptHistory(actor, other)).rejects.toThrow("unexpected response");
    await expect(listSettles(actor, other, null)).rejects.toThrow("unexpected response");
  });

  it("settles and rejects with fresh CSRF and the supplied retry keys", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(json(settle, 201))
      .mockResolvedValueOnce(json({ token: "two" }))
      .mockResolvedValueOnce(json({ ...settle, rejectedAt: "2026-11-16T13:00:00Z" }))
      .mockResolvedValueOnce(json({ token: "three" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ items: [settle], nextCursor: "next" }));
    vi.stubGlobal("fetch", fetch);
    expect((await createSettle(other, "sent", [lineId], "settle-key")).amountCents).toBe(100n);
    await rejectSettle(settleId, "reject-key");
    await acknowledgeReview(other, revision, "review-key");
    const page = await listSettles(actor, other, "abc");
    expect(page.nextCursor).toBe("next");
    expect(fetch.mock.calls[1]).toMatchObject([
      `/api/people/${other}/settles`,
      {
        method: "POST",
        headers: { "X-CSRF-TOKEN": "one", "Idempotency-Key": "settle-key" },
        body: JSON.stringify({ kind: "sent", lineIds: [lineId] }),
      },
    ]);
    expect(fetch.mock.calls[3]).toMatchObject([
      `/api/settles/${settleId}/reject`,
      { method: "POST", headers: { "X-CSRF-TOKEN": "two", "Idempotency-Key": "reject-key" } },
    ]);
    expect(fetch.mock.calls[5]?.[0]).toBe(`/api/users/${other}/settlement-review/acknowledge`);
    expect(fetch.mock.calls[6]?.[0]).toBe(`/api/people/${other}/settles?cursor=abc`);
  });

  it("keeps limit and conflict codes and rejects invalid identities before a request", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(
        new Response('{"code":"settle_limit","message":"Try again later."}', { status: 429 }),
      );
    vi.stubGlobal("fetch", fetch);
    await expect(createSettle(other, "sent", [lineId], "same-key")).rejects.toMatchObject({
      status: 429,
      code: "settle_limit",
    });
    await expect(createSettle(other, "sent", ["../bad"], "k")).rejects.toBeInstanceOf(BackendError);
    await expect(receiptHistory(actor, "../bad")).rejects.toBeInstanceOf(BackendError);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});

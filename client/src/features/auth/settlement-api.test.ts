import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "./backend-api";
import {
  acknowledgeReview,
  listPendingReviews,
  listSettlementCounterparties,
  listSettlementGroups,
  receiptHistory,
  transitionGroup,
  transitionLine,
} from "./settlement-api";

const actor = "123e4567-e89b-42d3-a456-426614174000";
const other = "123e4567-e89b-42d3-a456-426614174001";
const lineId = "123e4567-e89b-42d3-a456-426614174002";
const revision = "123e4567-e89b-42d3-a456-426614174003";
const line = {
  id: lineId,
  settlementId: revision,
  tableId: revision,
  payerId: actor,
  payeeId: other,
  amountCents: 100,
  status: "unpaid",
  revision: 1,
  forgiveRequestedAt: null,
  forgiveDeclinedAt: null,
  createdAt: "2026-11-11T12:00:00Z",
  updatedAt: "2026-11-11T12:00:00Z",
};
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });

afterEach(() => vi.unstubAllGlobals());

describe("gated settlement API", () => {
  it("preserves int64 cents exactly and rejects receipts for other parties", async () => {
    const hugeLine = JSON.stringify(line).replace(
      '"amountCents":100',
      '"amountCents":9223372036854775807',
    );
    const groups = `[{"otherUserId":"${other}","netCents":-9223372036854775808,"revision":"abc","pendingReviewRevision":null,"lines":[${hugeLine}]}]`;
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(groups))
      .mockResolvedValueOnce(json([{ ...line, payerId: other, payeeId: revision }]));
    vi.stubGlobal("fetch", fetch);
    const [group] = await listSettlementGroups(actor);
    expect(group?.netCents).toBe(-9223372036854775808n);
    expect(group?.lines[0]?.amountCents).toBe(9223372036854775807n);
    await expect(receiptHistory(actor, other)).rejects.toThrow("unexpected response");
  });

  it("uses fresh CSRF and supplied retry keys for review, line and group actions", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ token: "two" }))
      .mockResolvedValueOnce(json({ ...line, status: "sent", revision: 2 }))
      .mockResolvedValueOnce(json({ token: "three" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    await acknowledgeReview(other, revision, "review-key");
    await transitionLine(actor, other, lineId, "sent", 1, "line-key");
    await transitionGroup(other, "paid", "group-revision", "group-key");
    expect(fetch.mock.calls[1]).toMatchObject([
      `/api/users/${other}/settlement-review/acknowledge`,
      {
        method: "POST",
        headers: { "X-CSRF-TOKEN": "one", "Idempotency-Key": "review-key" },
        body: JSON.stringify({ revision }),
      },
    ]);
    expect(fetch.mock.calls[3]).toMatchObject([
      `/api/settlement-lines/${lineId}/sent`,
      { headers: { "X-CSRF-TOKEN": "two", "Idempotency-Key": "line-key" }, body: '{"revision":1}' },
    ]);
    expect(fetch.mock.calls[5]).toMatchObject([
      `/api/people/${other}/settle`,
      {
        headers: { "X-CSRF-TOKEN": "three", "Idempotency-Key": "group-key" },
        body: '{"step":"paid","expectedRevision":"group-revision"}',
      },
    ]);
  });

  it("retains conflict codes and rejects invalid identities before a request", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(new Response('{"code":"stale_review"}', { status: 409 }));
    vi.stubGlobal("fetch", fetch);
    await expect(acknowledgeReview(other, revision, "same-key")).rejects.toMatchObject({
      status: 409,
      code: "stale_review",
    });
    await expect(listPendingReviews("../bad")).rejects.toBeInstanceOf(BackendError);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("reads distinct counterparties and rejects the caller or duplicates", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([other]))
      .mockResolvedValueOnce(json([actor]))
      .mockResolvedValueOnce(json([other, other]));
    vi.stubGlobal("fetch", fetch);
    await expect(listSettlementCounterparties(actor)).resolves.toEqual([other]);
    expect(fetch.mock.calls[0]?.[0]).toBe("/api/people/settlement-counterparties");
    await expect(listSettlementCounterparties(actor)).rejects.toThrow("unexpected response");
    await expect(listSettlementCounterparties(actor)).rejects.toThrow("unexpected response");
  });
});

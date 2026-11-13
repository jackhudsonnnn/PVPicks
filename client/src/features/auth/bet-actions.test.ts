import { afterEach, describe, expect, it, vi } from "vitest";
import { executeBetAction } from "./bet-actions";

const userId = "123e4567-e89b-42d3-a456-426614174000";
const tableId = "123e4567-e89b-42d3-a456-426614174001";
const betId = "123e4567-e89b-42d3-a456-426614174002";
const bet = {
  id: betId,
  tableId,
  proposerId: userId,
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-11T12:00:00Z",
  pointsSettled: false,
  pickCount: 0,
  myPick: null,
  myParticipation: "not_joined",
};
const json = (value: unknown) => new Response(JSON.stringify(value));

afterEach(() => vi.unstubAllGlobals());

describe("gated bet actions", () => {
  it("uses CSRF and supplied retry keys for proposal, pick and clear", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(json(bet))
      .mockResolvedValueOnce(json({ token: "two" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ token: "three" }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetch);
    const proposal = {
      question: "Who wins?",
      choices: ["Yes", "No"],
      wagerCents: 100,
      durationSec: 60,
      forFun: false,
      settledBy: "hosts" as const,
    };
    await executeBetAction(userId, { kind: "propose", tableId, proposal }, "proposal-key");
    await executeBetAction(userId, { kind: "pick", tableId, betId, choice: "Yes" }, "pick-key");
    await executeBetAction(userId, { kind: "clear", tableId, betId }, "clear-key");
    expect(fetch.mock.calls[1]).toMatchObject([
      `/api/tables/${tableId}/bets`,
      {
        method: "POST",
        headers: { "X-CSRF-TOKEN": "one", "Idempotency-Key": "proposal-key" },
        body: JSON.stringify({ modeId: "u2pick", config: {}, ...proposal }),
      },
    ]);
    expect(fetch.mock.calls[3]).toMatchObject([
      `/api/bets/${betId}/pick`,
      {
        method: "PUT",
        headers: { "X-CSRF-TOKEN": "two", "Idempotency-Key": "pick-key" },
        body: '{"choice":"Yes"}',
      },
    ]);
    expect(fetch.mock.calls[5]).toMatchObject([
      `/api/bets/${betId}/pick`,
      { method: "DELETE", headers: { "X-CSRF-TOKEN": "three", "Idempotency-Key": "clear-key" } },
    ]);
    expect(fetch.mock.calls[5]?.[1]).not.toHaveProperty("body");
  });

  it("rejects leaked active picks and a mismatched proposal response", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: "one" }))
      .mockResolvedValueOnce(json({ ...bet, picks: [{ userId, choice: "Yes" }] }))
      .mockResolvedValueOnce(json({ token: "two" }))
      .mockResolvedValueOnce(json({ ...bet, proposerId: betId }));
    vi.stubGlobal("fetch", fetch);
    const proposal = {
      question: "Who wins?",
      choices: ["Yes", "No"],
      wagerCents: 100,
      durationSec: 60,
      forFun: false,
      settledBy: "hosts" as const,
    };
    await expect(
      executeBetAction(userId, { kind: "propose", tableId, proposal }, "first"),
    ).rejects.toThrow("unexpected response");
    await expect(
      executeBetAction(userId, { kind: "propose", tableId, proposal }, "second"),
    ).rejects.toThrow("unexpected response");
  });
});

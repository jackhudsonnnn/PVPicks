import { afterEach, describe, expect, it, vi } from "vitest";
import { listMyBets, type BackendBet } from "./backend-api";
import { listTableBalances } from "./tables-api";

const tableId = "123e4567-e89b-42d3-a456-426614174001";
const bet: BackendBet = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  tableId,
  proposerId: "123e4567-e89b-42d3-a456-426614174000",
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-13T12:00:00Z",
  createdAt: "2026-11-13T11:58:00Z",
  pointsSettled: false,
  pickCount: 0,
  myPick: null,
  myParticipation: "not_joined",
};
const counts = { ACTIVE: 1, PENDING: 0, SETTLED: 2, WASHED: 0 };

function respond(body: unknown) {
  const fetch = vi.fn(
    async (_input: string, _init?: RequestInit) =>
      new Response(JSON.stringify(body), { status: 200 }),
  );
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

afterEach(() => vi.unstubAllGlobals());

describe("my bets", () => {
  it("passes filters and the cursor as query parameters", async () => {
    const fetch = respond({ items: [bet], nextCursor: "abc", counts });
    const page = await listMyBets({ status: "ACTIVE", tableId, cursor: "xyz" });
    expect(fetch.mock.calls[0]?.[0]).toBe(
      `/api/me/bets?status=ACTIVE&tableId=${tableId}&cursor=xyz`,
    );
    expect(page.nextCursor).toBe("abc");
    expect(page.counts.SETTLED).toBe(2);
  });

  it("asks for everything without filters", async () => {
    const fetch = respond({ items: [], nextCursor: null, counts });
    await listMyBets({});
    expect(fetch.mock.calls[0]?.[0]).toBe("/api/me/bets");
  });

  it("rejects a page that doesn't match the requested status", async () => {
    respond({ items: [bet], nextCursor: null, counts });
    await expect(listMyBets({ status: "PENDING" })).rejects.toThrow("unexpected response");
  });

  it("rejects a malformed page", async () => {
    respond({ items: [bet], counts });
    await expect(listMyBets({})).rejects.toThrow();
  });
});

describe("table balances", () => {
  it("reads 64-bit balances exactly", async () => {
    respond([
      {
        userId: bet.proposerId,
        settleCents: 150,
        trueCents: -9_007_199_254_740_991,
      },
    ]);
    const [balance] = await listTableBalances(tableId);
    expect(balance).toEqual({
      userId: bet.proposerId,
      settleCents: 150n,
      trueCents: -9_007_199_254_740_991n,
    });
  });

  it("refuses an invalid table id without a request", async () => {
    const fetch = respond([]);
    await expect(listTableBalances("nope")).rejects.toThrow("Table not found");
    expect(fetch).not.toHaveBeenCalled();
  });
});

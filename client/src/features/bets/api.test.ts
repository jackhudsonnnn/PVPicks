import { afterEach, describe, expect, it, vi } from "vitest";
import { bet, json, table, user } from "@/test/api-fixtures";
import { listMyBets, listTableBets } from "./api";

const tableId = table.id;

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
    const fetch = respond({ items: [bet], nextCursor: "abc" });
    const page = await listMyBets({ status: "ACTIVE", tableId, cursor: "xyz" });
    expect(fetch.mock.calls[0]?.[0]).toBe(
      `/api/me/bets?status=ACTIVE&tableId=${tableId}&cursor=xyz`,
    );
    expect(page.nextCursor).toBe("abc");
  });

  it("asks for everything without filters", async () => {
    const fetch = respond({ items: [], nextCursor: null });
    await listMyBets({});
    expect(fetch.mock.calls[0]?.[0]).toBe("/api/me/bets");
  });

  it("rejects a page that doesn't match the requested status", async () => {
    respond({ items: [bet], nextCursor: null });
    await expect(listMyBets({ status: "PENDING" })).rejects.toThrow("unexpected response");
  });

  it("rejects a malformed page", async () => {
    respond({ items: [bet] });
    await expect(listMyBets({})).rejects.toThrow();
  });
});

describe("table bets", () => {
  it("reads active and locked projections and refuses an invalid table id without a request", async () => {
    const locked = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: user.id, choice: "Yes" }],
      noGuessUserIds: [],
    };
    const fetch = vi.fn().mockResolvedValueOnce(json([bet, locked]));
    vi.stubGlobal("fetch", fetch);
    await expect(listTableBets(tableId)).resolves.toEqual([bet, locked]);
    expect(fetch.mock.calls[0]?.[0]).toBe(`/api/tables/${tableId}/bets`);
    await expect(listTableBets("../auth/logout")).rejects.toMatchObject({ status: 404 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("rejects leaked active picks, malformed projections and bets from another table", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([{ ...bet, picks: [{ userId: user.id, choice: "No" }] }]))
      .mockResolvedValueOnce(json([{ ...bet, myPick: null }]))
      .mockResolvedValueOnce(json([{ ...bet, status: "PENDING" }]))
      .mockResolvedValueOnce(json([{ ...bet, tableId: user.id }]));
    vi.stubGlobal("fetch", fetch);
    for (let i = 0; i < 4; i++) {
      await expect(listTableBets(tableId)).rejects.toThrow("unexpected response");
    }
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { listTableBets } from "@/features/bets/api";
import { bet, json, summaryOf, table, user } from "@/test/api-fixtures";
import { getTable, listTableBalances, listTables } from "./api";

function respond(body: unknown) {
  const fetch = vi.fn(async () => json(body));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

afterEach(() => vi.unstubAllGlobals());

describe("tables API", () => {
  it("reads table list and detail and rejects invalid identities", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([summaryOf(table, user.id)]))
      .mockResolvedValueOnce(json(table));
    vi.stubGlobal("fetch", fetch);
    await expect(listTables()).resolves.toEqual([summaryOf(table, user.id)]);
    await expect(getTable(table.id)).resolves.toEqual(table);
    expect(fetch.mock.calls[1]?.[0]).toBe(`/api/tables/${table.id}`);
    await expect(getTable("../auth/logout")).rejects.toBeInstanceOf(BackendError);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("preserves generic UUIDs and validates string-keyed response records", async () => {
    const genericId = "123e4567-e89b-02d3-0456-426614174000";
    const withPermissions = summaryOf(
      { ...table, id: genericId, disabledPermissions: { [user.id]: ["bets"] } },
      user.id,
    );
    const withConfig = { ...bet, config: { options: ["Yes", "No"], count: 2 } };
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json([withPermissions]))
      .mockResolvedValueOnce(json([withConfig]))
      .mockResolvedValueOnce(json([{ ...withConfig, config: { options: { invalid: true } } }]));
    vi.stubGlobal("fetch", fetch);
    await expect(listTables()).resolves.toEqual([withPermissions]);
    await expect(listTableBets(table.id)).resolves.toEqual([withConfig]);
    await expect(listTableBets(table.id)).rejects.toThrow("unexpected response");
  });

  it("refuses a table list that carries more than the 100-table limit or whole member lists", async () => {
    const one = summaryOf(table, user.id);
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(Array.from({ length: 101 }, () => one)))
      .mockResolvedValueOnce(json([{ ...one, memberPreview: Array(5).fill(user.id) }]));
    vi.stubGlobal("fetch", fetch);
    await expect(listTables()).rejects.toThrow("unexpected response");
    await expect(listTables()).rejects.toThrow("unexpected response");
  });
});

describe("table balances", () => {
  it("reads 64-bit balances exactly", async () => {
    respond([
      {
        userId: user.id,
        settleCents: 150,
        trueCents: -9_007_199_254_740_991,
      },
    ]);
    const [balance] = await listTableBalances(table.id);
    expect(balance).toEqual({
      userId: user.id,
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

import { describe, expect, it } from "vitest";
import { summaryOf, table } from "@/test/api-fixtures";
import type { TableSummary } from "./api";
import { byRecentBet, newestFirst } from "./table-order";

const at = (name: string, joinedAt: string, lastBetAt?: string): TableSummary =>
  summaryOf({ ...table, id: name, name, disabledPermissions: {} }, table.memberIds[0]!, {
    joinedAt,
    ...(lastBetAt ? { lastBetAt } : {}),
  });

describe("table order", () => {
  it("puts the table with the most recent bet first, using join time when that's later or there are none", () => {
    const sorted = byRecentBet([
      at("Old bets", "2026-11-01T00:00:00Z", "2026-11-05T00:00:00Z"),
      at("Fresh bet", "2026-11-01T00:00:00Z", "2026-11-15T20:00:00Z"),
      at("Just joined", "2026-11-14T00:00:00Z"),
      at("Joined after its bet", "2026-11-15T21:00:00Z", "2026-11-10T00:00:00Z"),
    ]);
    expect(sorted.map((t) => t.name)).toEqual([
      "Joined after its bet",
      "Fresh bet",
      "Just joined",
      "Old bets",
    ]);
  });

  it("lists requests newest first", () => {
    const sorted = newestFirst([
      { id: "a", createdAt: "2026-11-10T00:00:00Z" },
      { id: "b", createdAt: "2026-11-15T00:00:00Z" },
      { id: "c", createdAt: "2026-11-12T00:00:00Z" },
    ]);
    expect(sorted.map((r) => r.id)).toEqual(["b", "c", "a"]);
  });
});

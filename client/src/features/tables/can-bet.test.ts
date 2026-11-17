import { describe, expect, it } from "vitest";
import { summaryOf, table, user } from "@/test/api-fixtures";
import type { Table } from "./api";
import { canBetAt } from "./api";

describe("canBetAt", () => {
  const whole = { ...table, remainderRule: "host" } as Table;
  it("reads your permission from the whole table or from your summary", () => {
    expect(canBetAt(whole, user.id)).toBe(true);
    const barred = { ...whole, disabledPermissions: { [user.id]: ["bets" as const] } };
    expect(canBetAt(barred, user.id)).toBe(false);
    expect(canBetAt(summaryOf(barred, user.id), user.id)).toBe(false);
    expect(canBetAt(summaryOf(whole, user.id), user.id)).toBe(true);
  });
});

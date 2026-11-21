import { describe, expect, it } from "vitest";
import { formatCents } from "@/lib/money";
import { PICK_WINDOWS, WAGER_DEFAULT_CENTS, WAGER_MAX_CENTS, WAGER_MIN_CENTS } from "./wager";

describe("wager amounts", () => {
  it("defaults to 1.00", () => {
    expect(WAGER_DEFAULT_CENTS).toBe(100);
  });

  it("slides from 0.10 to 10.00, the range the server accepts", () => {
    expect(WAGER_MIN_CENTS).toBe(10);
    expect(WAGER_MAX_CENTS).toBe(1000);
  });

  it("offers only pick windows the server accepts", () => {
    for (const window of PICK_WINDOWS) {
      expect(window.sec).toBeGreaterThanOrEqual(30);
      expect(window.sec).toBeLessThanOrEqual(600);
    }
  });
});

describe("points display", () => {
  it("shows cents as dollars-style points", () => {
    expect(formatCents(100)).toBe("1.00");
    expect(formatCents(250)).toBe("2.50");
    expect(formatCents(150, true)).toBe("+1.50");
    expect(formatCents(-400)).toBe("-4.00");
    expect(formatCents(0, true)).toBe("0.00");
    expect(formatCents(9_223_372_036_854_775_807n)).toMatch(/\.07$/);
  });
});

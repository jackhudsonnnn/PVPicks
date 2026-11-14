import { describe, expect, it } from "vitest";
import { formatCents } from "@/lib/money";
import { canStep, isValidWager, PICK_WINDOWS, stepWager, WAGER_DEFAULT_CENTS } from "./wager";

describe("wager amounts", () => {
  it("defaults to 1.00", () => {
    expect(WAGER_DEFAULT_CENTS).toBe(100);
  });

  it("steps by whole cents", () => {
    expect(stepWager(100, 1)).toBe(101);
    expect(stepWager(101, 10)).toBe(111);
    expect(stepWager(111, 100)).toBe(211);
  });

  it("never goes above 10.00", () => {
    expect(stepWager(950, 100)).toBe(1000);
    expect(canStep(950, 100)).toBe(false);
    expect(canStep(999, 1)).toBe(true);
    expect(canStep(1000, 1)).toBe(false);
  });

  it("allows 0.10 to 10.00 in whole cents only", () => {
    expect(isValidWager(10)).toBe(true);
    expect(isValidWager(1000)).toBe(true);
    expect(isValidWager(9)).toBe(false);
    expect(isValidWager(1001)).toBe(false);
    expect(isValidWager(10.5)).toBe(false);
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

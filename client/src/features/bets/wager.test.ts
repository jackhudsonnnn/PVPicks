import { describe, expect, it } from "vitest";
import { canStep, isValidWager, stepWager, WAGER_DEFAULT } from "./wager";
import { formatPts } from "./ledger";

describe("wager amounts", () => {
  it("defaults to 1.00", () => {
    expect(WAGER_DEFAULT).toBe(1);
  });

  it("steps by +0.01, +0.10 and +1.00 without float drift", () => {
    expect(stepWager(1, 0.01)).toBe(1.01);
    expect(stepWager(1.01, 0.1)).toBe(1.11);
    expect(stepWager(0.1, 0.1)).toBe(0.2);
    expect(stepWager(1.11, 1)).toBe(2.11);
  });

  it("never goes above 10.00", () => {
    expect(stepWager(9.5, 1)).toBe(10);
    expect(canStep(9.5, 1)).toBe(false);
    expect(canStep(9.99, 0.01)).toBe(true);
    expect(canStep(10, 0.01)).toBe(false);
  });

  it("allows 0.10 to 10.00 in whole cents only", () => {
    expect(isValidWager(0.1)).toBe(true);
    expect(isValidWager(10)).toBe(true);
    expect(isValidWager(0.09)).toBe(false);
    expect(isValidWager(10.01)).toBe(false);
    expect(isValidWager(1.005)).toBe(false);
  });
});

describe("points display", () => {
  it("shows dollars-style points to the nearest hundredth", () => {
    expect(formatPts(1)).toBe("1.00");
    expect(formatPts(2.5)).toBe("2.50");
    expect(formatPts(3.333)).toBe("3.33");
    expect(formatPts(1.5, true)).toBe("+1.50");
    expect(formatPts(-4)).toBe("-4.00");
    expect(formatPts(-0.001)).toBe("0.00");
  });
});

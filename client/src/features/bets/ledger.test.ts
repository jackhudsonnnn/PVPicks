import { describe, expect, it } from "vitest";
import { STARTING_POINTS, pointsBalance, record } from "./ledger";
import type { Bet } from "./types";

const resolved = (deltas: Record<string, number>, participants: Bet["participants"]) =>
  ({ id: "b", tableId: "t", status: "resolved", wager: 0.1, participants, deltas }) as Bet;

describe("points balance", () => {
  // Three winners split one 0.10 loss: 0.03 each, and the leftover cent goes to the host.
  const bet = resolved({ a: 0.03, b: 0.03, c: 0.03, d: -0.1, host: 0.01 }, [
    { userId: "a", selection: "Yes" },
    { userId: "b", selection: "Yes" },
    { userId: "c", selection: "Yes" },
    { userId: "d", selection: "No" },
  ]);

  it("a host who sat out still gets the leftover cent", () => {
    expect(pointsBalance("host", [bet])).toBe(STARTING_POINTS + 0.01);
  });

  it("the leftover cent isn't counted as a win for someone who sat out", () => {
    expect(record("host", [bet])).toEqual({ w: 0, l: 0 });
    expect(record("a", [bet])).toEqual({ w: 1, l: 0 });
  });

  it("a pending pot holds your wager", () => {
    const pending = { ...bet, status: "pending", deltas: undefined } as Bet;
    expect(pointsBalance("d", [pending])).toBe(STARTING_POINTS - 0.1);
    expect(pointsBalance("host", [pending])).toBe(STARTING_POINTS);
  });
});

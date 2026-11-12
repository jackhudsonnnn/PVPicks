import { describe, expect, it } from "vitest";
import type { Bet } from "@/features/bets/types";
import { peerScenarios } from "./scenarios";

const base = {
  tableId: "t",
  proposerId: "me",
  modeId: "either_or" as const,
  config: {},
  question: "?",
  choices: ["A", "B"],
  wager: 1,
  durationSec: 60,
  closesInSec: 0,
  createdAt: "",
};

const resolved: Bet = {
  ...base,
  id: "r",
  status: "resolved",
  participants: [],
  winningChoice: "A",
  deltas: { me: 1, bo: -1 },
};
const pending: Bet = {
  ...base,
  id: "p",
  status: "pending",
  participants: [
    { userId: "me", selection: "A" },
    { userId: "bo", selection: "B" },
    { userId: "cy", selection: "B" },
  ],
};

describe("peer balances", () => {
  const nets = peerScenarios([resolved, pending], "t");

  it("settle keeps only stored results and sums to 0", () => {
    expect(nets.get("me")!.settle).toBe(1);
    expect(nets.get("bo")!.settle).toBe(-1);
    expect(nets.get("cy")!.settle).toBe(0);
  });

  it("true = settle minus wagers in pending pots", () => {
    expect(nets.get("me")!.true).toBe(0);
    expect(nets.get("bo")!.true).toBe(-2);
    expect(nets.get("cy")!.true).toBe(-1);
  });



  it("4fun, active and other-table tickets are left out", () => {
    const n = peerScenarios(
      [
        { ...pending, forFun: true },
        { ...pending, status: "active" },
      ],
      "t",
    );
    expect(n.size).toBe(0);
    expect(peerScenarios([pending], "other").size).toBe(0);
  });
});

describe("cached peer balances", () => {
  it("reuses the result until the bets change", async () => {
    const { cachedPeerScenarios } = await import("./scenarios");
    const bets = [resolved, pending];
    expect(cachedPeerScenarios(bets, "t")).toBe(cachedPeerScenarios(bets, "t"));
    expect(cachedPeerScenarios([...bets], "t")).not.toBe(cachedPeerScenarios(bets, "t"));
  });
});

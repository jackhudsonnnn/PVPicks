import { describe, expect, it } from "vitest";
import { settleBet, settlementLines } from "./payout";
import { PASS } from "./types";

const choices = ["Patriots", "Bills"];
const three = [
  { userId: "a", selection: "Patriots" },
  { userId: "b", selection: "Patriots" },
  { userId: "c", selection: "Bills" },
];

describe("pooled payout", () => {
  it("splits the losers' pot evenly: two Patriots winners each gain 0.5", () => {
    const r = settleBet(three, 1, choices, "Patriots");
    expect(r.status).toBe("resolved");
    expect(r.deltas).toEqual({ a: 0.5, b: 0.5, c: -1 });
  });

  it("a lone Bills winner takes the combined 2 points", () => {
    const r = settleBet(three, 1, choices, "Bills");
    expect(r.deltas).toEqual({ a: -1, b: -1, c: 2 });
  });

  it("washes when everyone picks the same selection", () => {
    const same = three.map((p) => ({ ...p, selection: "Patriots" }));
    expect(settleBet(same, 1, choices, "Patriots").status).toBe("washed");
  });

  it("washes when nobody picks the winning choice", () => {
    const r = settleBet(three, 1, [...choices, "Neither"], "Neither");
    expect(r.status).toBe("washed");
  });

  it("washes when the outcome is outside the choices", () => {
    expect(settleBet(three, 1, choices, "Tie").status).toBe("washed");
  });

  it("players on Pass are not in the pot", () => {
    const withPass = [...three, { userId: "d", selection: PASS }];
    const r = settleBet(withPass, 1, choices, "Bills");
    expect(r.deltas["d"]).toBeUndefined();
    expect(r.deltas["c"]).toBe(2);
  });

  it("pays a sports pass selection while excluding no guess", () => {
    const participants = [
      { userId: "a", selection: "pass" },
      { userId: "b", selection: "run" },
      { userId: "c", selection: PASS },
    ];
    expect(settleBet(participants, 1, ["pass", "run"], "pass").deltas)
      .toEqual({ a: 1, b: -1 });
  });
});

describe("settle points through the host", () => {
  const bets = [
    { tableId: "t", status: "resolved", deltas: { host: 0.5, a: 0.5, b: -1 } },
    { tableId: "t", status: "resolved", deltas: { host: -2, a: 3, b: -1 } },
    { tableId: "t", status: "resolved", settled: true, deltas: { host: 10, a: -10 } },
    { tableId: "other", status: "resolved", deltas: { a: 5, b: -5 } },
    { tableId: "t", status: "pending" },
  ];

  it("winners get paid by the host, losers pay the host", () => {
    const { lines } = settlementLines(bets, "t", "host");
    expect(lines).toEqual([
      { userId: "b", amount: -2 },
      { userId: "a", amount: 3.5 },
    ]);
  });

  it("the host ends up with exactly their own result at the table", () => {
    // host: +0.5 - 2 = -1.5 → collects 2 from b, pays 3.5 to a = -1.5
    expect(settlementLines(bets, "t", "host").hostNet).toBe(-1.5);
  });

  it("ignores already-settled bets, other tables and unresolved bets", () => {
    const { lines } = settlementLines(bets.slice(2), "t", "host");
    expect(lines).toEqual([]);
  });
});

describe("leftover cents on uneven pots", () => {
  // 1 loser at 1 pt split 3 ways: 33¢ each, 1¢ left over.
  const four = [
    { userId: "a", selection: "Patriots" },
    { userId: "b", selection: "Patriots" },
    { userId: "c", selection: "Patriots" },
    { userId: "d", selection: "Bills" },
  ];
  const opts = (mode: "host" | "proposer" | "earliest" | "random") => ({
    mode,
    hostId: "h",
    proposerId: "c",
    seed: "bet1",
  });
  const sum = (d: Record<string, number>) =>
    Math.round(Object.values(d).reduce((s, n) => s + n, 0) * 100) + 0;

  it("host mode gives the odd cent to the host", () => {
    const r = settleBet(four, 1, choices, "Patriots", opts("host"));
    expect(r.deltas).toEqual({ a: 0.33, b: 0.33, c: 0.33, d: -1, h: 0.01 });
  });

  it("proposer mode gives it to the proposer", () => {
    expect(settleBet(four, 1, choices, "Patriots", opts("proposer")).deltas["c"]).toBe(0.34);
  });

  it("earliest mode gives it to the first winner to pick", () => {
    expect(settleBet(four, 1, choices, "Patriots", opts("earliest")).deltas["a"]).toBe(0.34);
  });

  it("random mode is repeatable for the same bet", () => {
    const x = settleBet(four, 1, choices, "Patriots", opts("random"));
    expect(settleBet(four, 1, choices, "Patriots", opts("random"))).toEqual(x);
  });

  it("every mode sums to exactly 0", () => {
    for (const m of ["host", "proposer", "earliest", "random"] as const) {
      expect(sum(settleBet(four, 1, choices, "Patriots", opts(m)).deltas)).toBe(0);
      expect(sum(settleBet(four, 2.5, choices, "Patriots", opts(m)).deltas)).toBe(0);
    }
  });

  it("a missing host falls back to the earliest winner instead of a blank id", () => {
    const r = settleBet(four, 1, choices, "Patriots", { ...opts("host"), hostId: "" });
    expect(r.deltas).toEqual({ a: 0.34, b: 0.33, c: 0.33, d: -1 });
  });

  it("a proposer who lost still gets the cent, and the total stays 0", () => {
    const r = settleBet(four, 1, choices, "Patriots", { ...opts("proposer"), proposerId: "d" });
    expect(r.deltas["d"]).toBe(-0.99);
    expect(sum(r.deltas)).toBe(0);
  });

  it("a single winner takes the whole pot with nothing left over", () => {
    const r = settleBet(four, 1, choices, "Bills", opts("host"));
    expect(r.deltas).toEqual({ a: -1, b: -1, c: -1, d: 3 });
  });

  it("random never gives one winner more than one extra cent", () => {
    // 2 losers at 1.00 split 7 ways: 28¢ each, 4¢ left over.
    const seven = [
      ..."abcdefg".split("").map((userId) => ({ userId, selection: "Patriots" })),
      { userId: "x", selection: "Bills" },
      { userId: "y", selection: "Bills" },
    ];
    const r = settleBet(seven, 1, choices, "Patriots", opts("random"));
    const wins = "abcdefg".split("").map((id) => r.deltas[id]);
    expect(wins.filter((d) => d === 0.29)).toHaveLength(4);
    expect(wins.filter((d) => d === 0.28)).toHaveLength(3);
    expect(sum(r.deltas)).toBe(0);
  });
});

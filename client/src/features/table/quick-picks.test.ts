import { describe, expect, it } from "vitest";
import type { Table } from "@/features/table/types";
import type { Bet } from "@/features/bets/types";
import {
  CODE_CHARS,
  canOverride,
  copyFor,
  followsSource,
  makeCode,
  normalizeCode,
  subscribeCheck,
  subscribersOf,
} from "./quick-picks";

const source: Table = {
  id: "src",
  name: "Section 112",
  emoji: "📣",
  memberIds: ["a"],
  hostIds: ["a"],
  quickPicksOn: true,
  shareCode: "K4R7QX",
};
const mine: Table = {
  id: "me",
  name: "Mine",
  emoji: "🏈",
  memberIds: ["h", "m"],
  hostIds: ["h"],
  quickPicksOn: true,
};

const bet: Bet = {
  id: "b1",
  tableId: "src",
  proposerId: "a",
  modeId: "u2pick",
  config: {},
  question: "Mascot falls?",
  choices: ["Yes", "No"],
  wager: 2.5,
  durationSec: 60,
  closesInSec: 40,
  status: "active",
  participants: [{ userId: "a", selection: "Yes" }],
  forFun: true,
  settledBy: "hosts",
  createdAt: "1:00 PM",
};

describe("share codes", () => {
  it("are 6 readable characters", () => {
    const c = makeCode();
    expect(c).toHaveLength(6);
    for (const ch of c) expect(CODE_CHARS).toContain(ch);
  });
  it("match regardless of case, spaces and dashes", () => {
    expect(normalizeCode(" k4r-7qx ")).toBe("K4R7QX");
  });
});

describe("subscribing (one-way: only the subscriber enters the source's code)", () => {
  it("finds the sharing table by code", () => {
    const r = subscribeCheck([source, mine], mine, "h", "k4r7qx");
    expect("source" in r && r.source.id).toBe("src");
  });
  it("needs a host", () => {
    expect(subscribeCheck([source, mine], mine, "m", "K4R7QX")).toEqual({
      error: "Only hosts can subscribe.",
    });
  });
  it("needs Quick Picks on", () => {
    expect(
      "error" in subscribeCheck([source], { ...mine, quickPicksOn: false }, "h", "K4R7QX"),
    ).toBe(true);
  });
  it("rejects an old code after the source made a new one", () => {
    const rotated = { ...source, shareCode: "ZZZZZZ" };
    expect("error" in subscribeCheck([rotated, mine], mine, "h", "K4R7QX")).toBe(true);
  });
  it("rejects its own code and duplicates", () => {
    const sharing = { ...mine, shareCode: "ABCDEF" };
    expect("error" in subscribeCheck([sharing], sharing, "h", "ABCDEF")).toBe(true);
    const subbed = { ...mine, subscribedTo: ["src"] };
    expect("error" in subscribeCheck([source, subbed], subbed, "h", "K4R7QX")).toBe(true);
  });
  it("lists subscribers only while Quick Picks is on", () => {
    const subbed = { ...mine, subscribedTo: ["src"] };
    expect(subscribersOf([subbed], "src")).toHaveLength(1);
    expect(subscribersOf([{ ...subbed, quickPicksOn: false }], "src")).toHaveLength(0);
  });
});

describe("copies", () => {
  it("are exact copies with no picks, labelled with the source", () => {
    const c = copyFor(bet, source, mine, "1:05 PM")!;
    expect(c.id).toBe("b1@me");
    expect(c.tableId).toBe("me");
    expect(c.participants).toEqual([]);
    expect([c.wager, c.forFun, c.closesInSec, c.choices]).toEqual([2.5, true, 40, ["Yes", "No"]]);
    expect(c.quickPick).toEqual({
      sourceTableId: "src",
      sourceBetId: "b1",
      sourceName: "Section 112",
    });
  });
  it("are never shared again", () => {
    const c = copyFor(bet, source, mine, "x")!;
    expect(copyFor(c, mine, { ...source, id: "other" }, "x")).toBeNull();
  });
  it("don't loop back when two tables subscribe to each other", () => {
    const c = copyFor(bet, source, mine, "x")!;
    expect(copyFor(c, mine, source, "x")).toBeNull();
  });
  it("skip U2Picks at a table with U2Pick off", () => {
    expect(copyFor(bet, source, { ...mine, u2pickOff: true }, "x")).toBeNull();
  });
  it("skip bets that already locked", () => {
    expect(copyFor({ ...bet, status: "pending" }, source, mine, "x")).toBeNull();
  });
});

describe("results and overrides", () => {
  const copy = { ...copyFor(bet, source, mine, "x")!, status: "pending" as const };
  it("pending copies follow the source", () => {
    expect(followsSource(copy)).toBe(true);
    expect(followsSource({ ...copy, quickPick: { ...copy.quickPick!, overridden: true } })).toBe(
      false,
    );
  });
  it("only a host at the copy's table can override, before points are settled", () => {
    expect(canOverride(mine, copy, "h")).toBe(true);
    expect(canOverride(mine, copy, "m")).toBe(false);
    expect(canOverride(mine, { ...copy, status: "resolved" }, "h")).toBe(true);
    expect(canOverride(mine, { ...copy, status: "resolved", settled: true }, "h")).toBe(false);
    expect(canOverride(mine, { ...copy, status: "active" }, "h")).toBe(false);
    expect(canOverride(source, bet, "a")).toBe(false);
  });
});

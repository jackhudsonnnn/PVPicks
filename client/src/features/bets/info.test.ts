import { describe, expect, it } from "vitest";
import { betInfo, makeBaseline } from "./info";
import type { GameSnapshot } from "./live-stats";
import type { Bet } from "./types";

const snap = (away: number, home: number, stats: Record<string, number> = {}): GameSnapshot => ({
  awayScore: away,
  homeScore: home,
  possession: "away",
  yardLine: "KC 30",
  stat: (p, s) => stats[`${p}|${s}`] ?? 0,
});

const bet = (over: Partial<Bet>): Bet => ({
  id: "b",
  tableId: "t",
  proposerId: "u",
  modeId: "either_or",
  config: {},
  question: "",
  choices: [],
  wager: 1,
  durationSec: 60,
  closesInSec: 60,
  status: "active",
  participants: [],
  createdAt: "now",
  ...over,
});
const val = (rows: { label: string; value: string }[], label: string) =>
  rows.find((r) => r.label === label)?.value;

describe("bet info", () => {
  it("Either Or shows each player's initial, current and net stat", () => {
    const config = { playerA: "a", playerB: "b", stat: "Points" };
    const baseline = makeBaseline({ modeId: "either_or", config }, snap(0, 0, { "a|Points": 10, "b|Points": 4 }), "t");
    const rows = betInfo(bet({ config, baseline }), undefined, snap(0, 0, { "a|Points": 15, "b|Points": 12 }));
    expect(val(rows, "a initial Points")).toBe("10");
    expect(val(rows, "a net Points")).toBe("+5");
    expect(val(rows, "b net Points")).toBe("+8");
    expect(val(rows, "Includes overtime?")).toBe("Yes");
  });
  it("Give and Take adds the spread to the home score", () => {
    const rows = betInfo(bet({ modeId: "give_and_take", config: { spread: -3.5 } }), undefined, snap(20, 24));
    expect(val(rows, "Home score adjusted")).toBe("20.5");
  });
  it("Choose Their Fate shows possession and starting yard line, no overtime row", () => {
    const baseline = makeBaseline({ modeId: "choose_their_fate", config: {} }, snap(0, 0), "t");
    const rows = betInfo(bet({ modeId: "choose_their_fate", config: { team: "away" }, baseline }), undefined, snap(0, 0));
    expect(val(rows, "Possessing team")).toBe("Away");
    expect(val(rows, "Yard line")).toBe("KC 30");
    expect(val(rows, "Includes overtime?")).toBeUndefined();
  });
  it("winning choice stays empty until resolved", () => {
    expect(val(betInfo(bet({ status: "pending", winningChoice: "X" }), undefined, undefined), "Winning choice")).toBe("—");
    expect(val(betInfo(bet({ status: "resolved", winningChoice: "X" }), undefined, undefined), "Winning choice")).toBe("X");
  });
});

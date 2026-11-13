import { describe, expect, it } from "vitest";
import type { Table } from "@/features/table/types";
import { canSettleCustom, cleanOptions, customBetProblem, eligibleSettlers } from "./u2pick";
import { PASS, type Bet, type SettlerRule } from "./types";

const table: Table = {
  id: "t",
  name: "T",
  emoji: "🍺",
  memberIds: ["host", "prop", "a", "b", "watcher"],
  hostIds: ["host"],
};

const bet = (settledBy: SettlerRule, participants = ["prop", "a", "b"]): Bet => ({
  id: "x",
  tableId: "t",
  proposerId: "prop",
  modeId: "u2pick",
  config: {},
  question: "Jack to spill beer on his shirt tonight?",
  choices: ["Yes", "No"],
  settledBy,
  wager: 2,
  durationSec: 60,
  closesInSec: 0,
  status: "pending",
  participants: [
    ...participants.map((userId, i) => ({ userId, selection: i % 2 ? "No" : "Yes" })),
    { userId: "b2", selection: PASS },
  ],
  createdAt: "now",
});

describe("who can settle a U2Pick bet", () => {
  it("anyone: every table member, but not outsiders", () => {
    expect(eligibleSettlers(bet("anyone"), table)).toEqual(table.memberIds);
    expect(canSettleCustom(bet("anyone"), table, "stranger")).toBe(false);
  });

  it("me: only the proposer", () => {
    expect(eligibleSettlers(bet("me"), table)).toEqual(["prop"]);
  });

  it("hosts: only hosts", () => {
    expect(eligibleSettlers(bet("hosts"), table)).toEqual(["host"]);
  });

  it("non-participants: only members not in the pot", () => {
    expect(eligibleSettlers(bet("non_participants"), table)).toEqual(["host", "watcher"]);
  });

  it("non-participants: someone on Pass counts as not in the pot", () => {
    const t = { ...table, memberIds: [...table.memberIds, "b2"] };
    expect(canSettleCustom(bet("non_participants"), t, "b2")).toBe(true);
  });

  it("non-participants: if everyone is in the pot, hosts call it", () => {
    const all = bet("non_participants", table.memberIds);
    expect(eligibleSettlers(all, table)).toEqual(["host"]);
  });
});

describe("custom bet options", () => {
  it("trims, drops blanks and duplicates", () => {
    expect(cleanOptions([" Yes ", "", "no", "NO", "Maybe"])).toEqual(["Yes", "no", "Maybe"]);
  });

  it("needs a question and at least two different options", () => {
    expect(customBetProblem("", ["Yes", "No"])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", ""])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", "yes"])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", "No"])).toBeNull();
  });

  it("allows sports pass choices but reserves the no-guess state", () => {
    expect(customBetProblem("Next play?", ["Pass", "Run"])).toBeNull();
    expect(customBetProblem("Q?", ["Yes", "no guess"])).not.toBeNull();
    expect(customBetProblem("Q?", ["Yes", PASS])).not.toBeNull();
  });

  it("allows at most 6 options", () => {
    expect(customBetProblem("Q?", ["1", "2", "3", "4", "5", "6"])).toBeNull();
    expect(customBetProblem("Q?", ["1", "2", "3", "4", "5", "6", "7"])).not.toBeNull();
  });

  it("counts Unicode code points at the question and option limits", () => {
    expect(customBetProblem("🎉".repeat(120), ["🎉".repeat(40), "No"])).toBeNull();
    expect(customBetProblem("🎉".repeat(121), ["Yes", "No"])).not.toBeNull();
    expect(customBetProblem("Q?", ["🎉".repeat(41), "No"])).not.toBeNull();
  });
});

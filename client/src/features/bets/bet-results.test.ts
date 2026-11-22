import { describe, expect, it } from "vitest";
import type { Bet } from "./api";
import { awaitingPointsSettlement } from "./bet-results";

const locked: Bet = {
  id: "123e4567-e89b-42d3-a456-426614174020",
  tableId: "123e4567-e89b-42d3-a456-426614174010",
  proposerId: "123e4567-e89b-42d3-a456-426614174001",
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "PENDING",
  locksAt: "2026-11-11T12:00:00Z",
  createdAt: "2026-11-11T11:58:00Z",
  pointsSettled: false,
  pickCount: 2,
  myPick: null,
  myParticipation: "not_joined",
  viewerCanCall: false,
  viewerCanOverride: false,
  picks: [],
  noGuessUserIds: [],
};

describe("awaitingPointsSettlement", () => {
  it("counts only resolved, unsettled, non-4fun bets", () => {
    expect(
      awaitingPointsSettlement([
        { ...locked, status: "SETTLED" },
        { ...locked, status: "SETTLED", pointsSettled: true },
        { ...locked, status: "SETTLED", forFun: true },
        { ...locked, status: "WASHED" },
        locked,
      ]),
    ).toBe(1);
  });
});

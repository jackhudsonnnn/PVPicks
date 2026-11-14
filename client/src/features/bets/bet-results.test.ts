import { describe, expect, it } from "vitest";
import type { BackendBet, BackendTable } from "./backend-api";
import { awaitingPointsSettlement, canCallResult } from "./bet-results";

const host = "123e4567-e89b-42d3-a456-426614174000";
const player = "123e4567-e89b-42d3-a456-426614174001";
const outsider = "123e4567-e89b-42d3-a456-426614174002";
const table: BackendTable = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  name: "Table",
  emoji: "T",
  memberIds: [host, player, outsider],
  hostIds: [host],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
  disabledPermissions: {},
};
const locked: BackendBet = {
  id: "123e4567-e89b-42d3-a456-426614174020",
  tableId: table.id,
  proposerId: player,
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
  picks: [
    { userId: host, choice: "Yes" },
    { userId: player, choice: "No" },
  ],
  noGuessUserIds: [],
  remainderRule: "host",
};

describe("canCallResult", () => {
  it("follows each settler rule", () => {
    const callers = (bet: BackendBet) =>
      [host, player, outsider].filter((id) => canCallResult(bet, table, id));
    expect(callers({ ...locked, settledBy: "anyone" })).toEqual([host, player, outsider]);
    expect(callers({ ...locked, settledBy: "hosts" })).toEqual([host]);
    expect(callers(locked)).toEqual([host]);
    expect(callers({ ...locked, settledBy: "me" })).toEqual([player]);
    expect(callers({ ...locked, settledBy: "non_participants" })).toEqual([outsider]);
  });

  it("lets hosts call a non-participant bet when everyone is in the pot", () => {
    const everyone = {
      ...locked,
      settledBy: "non_participants" as const,
      picks: [...locked.picks!, { userId: outsider, choice: "Yes" }],
    };
    expect(canCallResult(everyone, table, host)).toBe(true);
    expect(canCallResult(everyone, table, player)).toBe(false);
  });

  it("offers nothing for active or resolved bets, or to non-members", () => {
    expect(canCallResult({ ...locked, status: "SETTLED" }, table, host)).toBe(false);
    expect(
      canCallResult({ ...locked, settledBy: "anyone" }, { ...table, memberIds: [host] }, player),
    ).toBe(false);
  });
});

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

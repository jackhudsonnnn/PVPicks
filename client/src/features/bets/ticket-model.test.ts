import { describe, expect, it } from "vitest";
import type { BackendBet, BackendTable } from "./backend-api";
import {
  activeHint,
  choiceTallies,
  formatClock,
  myResultCents,
  participantIds,
  potCents,
  proposeBlock,
  secondsLeft,
  ticketStatus,
} from "./ticket-model";

const me = "123e4567-e89b-42d3-a456-426614174000";
const a = "123e4567-e89b-42d3-a456-426614174001";
const b = "123e4567-e89b-42d3-a456-426614174002";

const active: BackendBet = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  tableId: "123e4567-e89b-42d3-a456-426614174020",
  proposerId: a,
  modeId: "u2pick",
  config: {},
  question: "Spill?",
  choices: ["Yes", "No", "Maybe"],
  wagerCents: 150,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-13T12:02:00Z",
  createdAt: "2026-11-13T12:00:00Z",
  pointsSettled: false,
  pickCount: 2,
  myPick: "Yes",
  myParticipation: "picked",
};

const locked: BackendBet = {
  ...active,
  status: "PENDING",
  picks: [
    { userId: me, choice: "Yes" },
    { userId: a, choice: "Yes" },
    { userId: b, choice: "No" },
  ],
  noGuessUserIds: ["123e4567-e89b-42d3-a456-426614174003"],
  remainderRule: "host",
};

describe("ticket model", () => {
  it("maps server statuses to ticket statuses", () => {
    expect(ticketStatus(active)).toBe("active");
    expect(ticketStatus({ ...locked, status: "SETTLED" })).toBe("resolved");
    expect(ticketStatus({ ...locked, status: "WASHED" })).toBe("washed");
  });

  it("counts down to the lock time and never goes negative", () => {
    const now = Date.parse("2026-11-13T12:00:30.200Z");
    expect(secondsLeft(active, now)).toBe(90);
    expect(formatClock(90)).toBe("1:30");
    expect(formatClock(7509)).toBe("2:05:09");
    expect(formatClock(30 * 86_400 + 4 * 3600)).toBe("30d 4h");
    expect(secondsLeft(active, Date.parse("2026-11-13T12:05:00Z"))).toBe(0);
  });

  it("keeps picks, pot and participants private until lock", () => {
    expect(choiceTallies(active)).toBeNull();
    expect(potCents(active)).toBeNull();
    expect(participantIds(active)).toBeNull();
  });

  it("tallies revealed picks; No guess is not in the pot", () => {
    expect(choiceTallies(locked)).toEqual([
      { choice: "Yes", count: 2, pct: 67 },
      { choice: "No", count: 1, pct: 33 },
      { choice: "Maybe", count: 0, pct: 0 },
    ]);
    expect(potCents(locked)).toBe(450);
    expect(potCents({ ...locked, forFun: true })).toBe(0);
    expect(participantIds(locked)).toEqual([me, a, b, "123e4567-e89b-42d3-a456-426614174003"]);
  });

  it("shows your result only on a resolved bet you picked", () => {
    const resolved = { ...locked, status: "SETTLED" as const, deltasCents: { [b]: -150 } };
    expect(myResultCents(resolved, me)).toBe(0);
    expect(myResultCents({ ...resolved, deltasCents: { [me]: 75 } }, me)).toBe(75);
    expect(myResultCents(locked, me)).toBeNull();
    expect(
      myResultCents({ ...resolved, myPick: null, myParticipation: "no_guess" }, me),
    ).toBeNull();
  });

  it("explains what tapping a choice does", () => {
    expect(
      activeHint({ ...active, myPick: null, myParticipation: "not_joined" }, true, "1.50"),
    ).toBe("Tap a choice to join. 1.50 pts goes in the pot when picks lock.");
    expect(activeHint(active, false, "1.50")).toMatch(/turned off betting/);
    expect(
      activeHint({ ...active, myPick: null, myParticipation: "no_guess" }, true, "1.50"),
    ).toMatch(/not made a guess/);
  });
});

describe("who can propose", () => {
  const table: BackendTable = {
    id: active.tableId,
    name: "T",
    emoji: "🍺",
    memberIds: [me, a],
    hostIds: [a],
    remainderRule: "host",
    hostsOnlyBets: false,
    u2pickEnabled: true,
    quickPicksEnabled: false,
    quickPickSharing: false,
    subscribedTo: [],
    disabledPermissions: {},
  };

  it("allows members with betting on", () => {
    expect(proposeBlock(table, me)).toBeNull();
  });

  it("explains each block", () => {
    expect(proposeBlock({ ...table, disabledPermissions: { [me]: ["bets"] } }, me)).toMatch(
      /turned off betting/,
    );
    expect(proposeBlock({ ...table, hostsOnlyBets: true }, me)).toMatch(/Only hosts/);
    expect(proposeBlock({ ...table, hostsOnlyBets: true }, a)).toBeNull();
    expect(proposeBlock({ ...table, u2pickEnabled: false }, a)).toMatch(/U2Pick/);
  });
});

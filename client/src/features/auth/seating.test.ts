import { describe, expect, it } from "vitest";
import type { BackendBet } from "./backend-api";
import { arrangeSeats, openBets, seatPosition, seatStatus } from "./seating";

const ids = Array.from(
  { length: 10 },
  (_, i) => `123e4567-e89b-42d3-a456-4266141740${i.toString().padStart(2, "0")}`,
);
const [me, a, b, c] = ids as [string, string, string, string];

const active: BackendBet = {
  id: "123e4567-e89b-42d3-a456-426614174099",
  tableId: "123e4567-e89b-42d3-a456-426614174098",
  proposerId: a,
  modeId: "u2pick",
  config: {},
  question: "Q?",
  choices: ["Yes", "No"],
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-13T12:02:00Z",
  createdAt: "2026-11-13T12:00:00Z",
  pointsSettled: false,
  pickCount: 1,
  myPick: "Yes",
  myParticipation: "picked",
};
const locked: BackendBet = {
  ...active,
  status: "PENDING",
  picks: [
    { userId: me, choice: "Yes" },
    { userId: b, choice: "No" },
  ],
  noGuessUserIds: [c],
  remainderRule: "proposer",
};

describe("seating", () => {
  it("seats everyone while a bet is open for picks, you at the bottom", () => {
    expect(arrangeSeats([a, me, b], me, active)).toEqual({ seated: [me, a, b], rail: [] });
    expect(arrangeSeats([a, me, b], me, undefined)).toEqual({ seated: [me, a, b], rail: [] });
  });

  it("seats only the people in a locked bet, keeping table order", () => {
    expect(arrangeSeats([a, b, me, c], me, locked).seated).toEqual([me, b, c]);
    expect(arrangeSeats([a, b, c], a, locked).seated).toEqual([b, c]);
  });

  it("moves overflow to an 'others' spot", () => {
    const { seated, rail } = arrangeSeats(ids, me, undefined);
    expect(seated).toHaveLength(7);
    expect(rail).toEqual(ids.slice(7));
    expect(arrangeSeats(ids.slice(0, 8), me, undefined).rail).toEqual([]);
  });

  it("puts seat 0 at the bottom center", () => {
    const pos = seatPosition(0, 4);
    expect(pos.left).toBeCloseTo(50);
    expect(pos.top).toBeCloseTo(100);
  });
});

describe("seat chips", () => {
  it("shows only your own pick while Active", () => {
    expect(seatStatus(active, me, me)).toEqual({ label: "Yes", tone: "pick" });
    expect(seatStatus(active, b, me)).toBeNull();
    expect(seatStatus({ ...active, myPick: null, myParticipation: "no_guess" }, me, me)).toEqual({
      label: "No guess",
      tone: "pass",
    });
  });

  it("shows everyone's pick once locked", () => {
    expect(seatStatus(locked, b, me)).toEqual({ label: "No", tone: "pick" });
    expect(seatStatus(locked, c, me)).toEqual({ label: "No guess", tone: "pass" });
    expect(seatStatus(locked, a, me)).toBeNull();
  });

  it("shows results after resolution", () => {
    const resolved = {
      ...locked,
      status: "SETTLED" as const,
      winningChoice: "Yes",
      deltasCents: { [me]: 100, [b]: -100 },
    };
    expect(seatStatus(resolved, me, me)).toEqual({ label: "Won +1.00", tone: "win" });
    expect(seatStatus(resolved, b, me)).toEqual({ label: "Lost 1.00", tone: "loss" });
    expect(seatStatus({ ...resolved, forFun: true }, b, me)).toEqual({
      label: "Lost",
      tone: "loss",
    });
    expect(seatStatus({ ...locked, status: "WASHED" }, b, me)).toEqual({
      label: "Washed",
      tone: "washed",
    });
  });

  it("orders the deck newest first and drops closed bets", () => {
    const newer = { ...active, id: "n", createdAt: "2026-11-13T12:05:00Z" };
    const closed = { ...locked, id: "c", status: "SETTLED" as const };
    expect(openBets([active, closed, newer]).map((bet) => bet.id)).toEqual(["n", active.id]);
  });
});

import { describe, expect, it } from "vitest";
import type { BackendBet } from "./backend-api";
import { betsInvolving, newThrows, profileStats } from "./profile-model";

const me = "123e4567-e89b-42d3-a456-426614174000";
const them = "123e4567-e89b-42d3-a456-426614174001";
const other = "123e4567-e89b-42d3-a456-426614174002";

const base: BackendBet = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  tableId: "123e4567-e89b-42d3-a456-426614174020",
  proposerId: other,
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
  myPick: null,
  myParticipation: "not_joined",
};
const bet = (id: string, patch: Partial<BackendBet>): BackendBet => ({ ...base, id, ...patch });

const won = bet("w", {
  status: "SETTLED",
  createdAt: "2026-11-13T12:03:00Z",
  picks: [
    { userId: them, choice: "Yes" },
    { userId: other, choice: "No" },
  ],
  noGuessUserIds: [],
  deltasCents: { [them]: 100, [other]: -100 },
});
const lost = bet("l", {
  status: "SETTLED",
  createdAt: "2026-11-13T12:04:00Z",
  picks: [{ userId: them, choice: "No" }],
  noGuessUserIds: [],
  deltasCents: { [them]: -250 },
});
const fun = bet("f", { ...won, id: "f", forFun: true, deltasCents: {} });
const activeTheirs = bet("a", { proposerId: them });
const activeHidden = bet("h", { pickCount: 3 });
const activeMine = bet("m", { myParticipation: "picked", myPick: "Yes" });

describe("profile bets", () => {
  it("shows bets they proposed or visibly joined, newest first", () => {
    expect(
      betsInvolving([won, lost, activeTheirs, activeHidden], them, me).map((b) => b.id),
    ).toEqual(["l", "w", "a"]);
  });

  it("never reveals someone else's Active participation, only your own", () => {
    expect(betsInvolving([activeHidden, activeMine], them, me)).toEqual([]);
    expect(betsInvolving([activeHidden, activeMine], me, me).map((b) => b.id)).toEqual(["m"]);
  });

  it("totals resolved results, skipping 4fun", () => {
    expect(profileStats([won, lost, fun, activeTheirs], them)).toEqual({
      played: 2,
      won: 1,
      lost: 1,
      netCents: -150,
    });
  });
});

describe("tomato queue", () => {
  const t = (id: string, at: string) => ({ id, throwerId: me, targetId: them, thrownAt: at });

  it("animates only unseen throws made after you started watching", () => {
    const since = Date.parse("2026-11-13T12:00:00Z");
    const throws = [
      t("old", "2026-11-13T11:59:59Z"),
      t("seen", "2026-11-13T12:00:01Z"),
      t("new", "2026-11-13T12:00:02Z"),
    ];
    expect(newThrows(throws, new Set(["seen"]), since).map((x) => x.id)).toEqual(["new"]);
  });
});

import { describe, expect, it } from "vitest";
import { PASS, type Bet } from "@/features/bets/types";
import { seedFor } from "@/features/mock/profiles";
import { ME } from "@/features/mock/seed";
import {
  LIVE_RATE,
  liveTick,
  outcomes,
  pointsMoved,
  ticketsByDay,
  zeroSumAudit,
} from "./analytics";

const bet = (id: string, extra: Partial<Bet>): Bet => ({
  id,
  tableId: "t",
  proposerId: "a",
  modeId: "either_or",
  config: {} as Bet["config"],
  question: "?",
  choices: ["X", "Y"],
  wager: 1,
  durationSec: 60,
  closesInSec: 0,
  status: "resolved",
  createdAt: "7:00 PM",
  participants: [
    { userId: "a", selection: "X" },
    { userId: "b", selection: "Y" },
    { userId: "c", selection: PASS },
  ],
  ...extra,
});

describe("admin analytics", () => {
  it("flags any resolved ticket whose points don't add to zero", () => {
    const ok = bet("ok", { deltas: { a: 1, b: -1 } });
    const bad = bet("bad", { deltas: { a: 1, b: -0.5 } });
    expect(zeroSumAudit([ok, bad])).toEqual({ checked: 2, broken: ["bad"] });
  });

  it("every sample player's history is zero-sum", () => {
    for (const id of ["newbie", "intermediate", "pro"] as const) {
      expect(zeroSumAudit(seedFor(id).bets).broken).toEqual([]);
    }
  });

  it("points moved counts only the winners' side, and skips 4fun tickets", () => {
    expect(
      pointsMoved([
        bet("x", { deltas: { a: 2, b: -2 } }),
        bet("f", { forFun: true, deltas: { a: 5 } }),
      ]),
    ).toBe(2);
  });

  it("outcomes split wins, losses, washes and passes", () => {
    const bets = [
      bet("w", { deltas: { a: 1, b: -1 } }),
      bet("l", { deltas: { a: -1, b: 1 } }),
      bet("wash", { status: "washed" }),
      bet("open", { status: "active" }),
    ];
    expect(outcomes("a", bets)).toEqual({ win: 1, loss: 1, wash: 1, pass: 0 });
    expect(outcomes("c", bets)).toEqual({ win: 0, loss: 0, wash: 0, pass: 3 });
  });

  it("groups tickets by day with today last", () => {
    const days = ticketsByDay([
      bet("1", { createdAt: "Oct 2 1:00 PM" }),
      bet("2", {}),
      bet("3", { createdAt: "Oct 10 2:00 PM" }),
    ]);
    expect(days.map((d) => d.day)).toEqual(["Oct 2", "Oct 10", "Today"]);
  });

  it("live samples stay in each player's range and keep the last 30", () => {
    let pts = liveTick([], () => 1);
    expect(pts[0]).toMatchObject({ t: 1, newbie: LIVE_RATE.newbie[1], pro: LIVE_RATE.pro[1] });
    for (let i = 0; i < 40; i++) pts = liveTick(pts, () => 0);
    expect(pts).toHaveLength(30);
    expect(pts[29]!.pro).toBe(LIVE_RATE.pro[0]);
  });

  it("pro's deleted sample player shows as Deleted user but keeps their results", () => {
    const pro = seedFor("pro");
    const gone = pro.users.find((u) => u.deleted)!;
    expect(gone.username).toBe("Deleted user");
    expect(pro.tables.some((t) => t.memberIds.includes(gone.id))).toBe(false);
    expect(pro.bets.some((b) => b.status === "resolved" && b.deltas?.[gone.id] !== undefined)).toBe(
      true,
    );
    expect(pro.tables.some((t) => t.memberIds.includes(ME))).toBe(true);
  });
});

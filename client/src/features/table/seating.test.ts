import { describe, expect, it } from "vitest";
import { PASS, type Bet } from "@/features/bets/types";
import { arrangeSeats, seatPosition, seatStatus } from "./seating";

const members = Array.from({ length: 16 }, (_, i) => `u${i}`);
const bet = (participants: Bet["participants"], status: Bet["status"] = "active") =>
  ({ participants, status }) as Bet;

describe("table seating", () => {
  it("with more than 8 members, seats 7 and the rest go to the 'others' spot", () => {
    const { seated, rail } = arrangeSeats(members, "u0");
    expect(seated).toHaveLength(7);
    expect(rail).toHaveLength(9);
  });

  it("9 members: 7 seated plus '2 others'", () => {
    const { seated, rail } = arrangeSeats(members.slice(0, 9), "u0");
    expect([seated.length, rail.length]).toEqual([7, 2]);
  });

  it("8 or fewer members all get a seat and no 'others' spot", () => {
    const { seated, rail } = arrangeSeats(members.slice(0, 8), "u0");
    expect([seated.length, rail.length]).toEqual([8, 0]);
  });

  it("you always take the first seat", () => {
    expect(arrangeSeats(members, "u5").seated[0]).toBe("u5");
  });

  it("only selected active bet participants get seats, not even you otherwise", () => {
    const b = bet([
      { userId: "u14", selection: "A" },
      { userId: "u15", selection: "B" },
    ]);
    expect(arrangeSeats(members, "u0", b)).toEqual({
      seated: ["u14", "u15"], rail: [],
    });
  });

  it("includes Pass participants in pending bets, with you first if participating", () => {
    const b = bet([
      { userId: "u2", selection: "A" },
      { userId: "u5", selection: PASS },
    ], "pending");
    expect(arrangeSeats(members, "u5", b)).toEqual({ seated: ["u5", "u2"], rail: [] });
  });

  it("counts only selected bet participants in the overflow spot", () => {
    const b = bet(members.slice(4, 13).map((userId) => ({ userId, selection: "A" })));
    expect(arrangeSeats(members, "u0", b)).toEqual({
      seated: members.slice(4, 11), rail: ["u11", "u12"],
    });
  });

  it("an open bet with no participants leaves the felt empty", () => {
    expect(arrangeSeats(members, "u0", bet([]))).toEqual({ seated: [], rail: [] });
  });

  it.each(["resolved", "washed"] as const)("shows everyone when no open bets remain (%s)", (status) => {
    expect(arrangeSeats(members, "u0", bet([{ userId: "u14", selection: "A" }], status)))
      .toEqual(arrangeSeats(members, "u0"));
  });

  it("switching selected bets replaces seats rather than combining participants", () => {
    const first = bet([{ userId: "u2", selection: "A" }]);
    const next = bet([{ userId: "u4", selection: "B" }], "pending");
    expect(arrangeSeats(members, "u0", first).seated).toEqual(["u2"]);
    expect(arrangeSeats(members, "u0", next).seated).toEqual(["u4"]);
  });

  it("keeps table order regardless of join order and assigns unique destinations", () => {
    const participants = ["u7", "u3", "u1", "u6", "u2", "u0", "u5", "u4", "u8"];
    for (let count = 1; count <= participants.length; count++) {
      const b = bet(participants.slice(0, count).map((userId) => ({ userId, selection: "A" })));
      const { seated, rail } = arrangeSeats(members, "u0", b);
      const eligible = members.filter((id) => participants.slice(0, count).includes(id));
      expect([...seated, ...rail]).toEqual(eligible);
      const spots = seated.length + (rail.length > 0 ? 1 : 0);
      const positions = Array.from({ length: spots }, (_, i) => JSON.stringify(seatPosition(i, Math.max(spots, 2))));
      expect(new Set(positions).size).toBe(spots);
    }
  });
});

describe("seat status chip", () => {
  it("labels a participant without a guess", () => {
    expect(seatStatus(bet([{ userId: "x", selection: PASS }], "pending"), "x", "me"))
      .toEqual({ label: "No guess", tone: "pass" });
  });

  it("hides other players' picks while the bet is active", () => {
    const b = bet([{ userId: "x", selection: "Over" }]);
    expect(seatStatus(b, "x", "me")).toEqual({ label: "In", tone: "in" });
  });

  it("reveals picks once the bet is pending", () => {
    const b = bet([{ userId: "x", selection: "Over" }], "pending");
    expect(seatStatus(b, "x", "me")?.label).toBe("Over");
  });

  it("4fun winners show Won, not 'Lost 0.00'", () => {
    const b = {
      ...bet(
        [
          { userId: "x", selection: "Over" },
          { userId: "y", selection: "Under" },
        ],
        "resolved",
      ),
      forFun: true,
      winningChoice: "Over",
      deltas: {},
    } as Bet;
    expect(seatStatus(b, "x", "me")).toEqual({ label: "Won", tone: "win" });
    expect(seatStatus(b, "y", "me")).toEqual({ label: "Lost", tone: "loss" });
  });
});

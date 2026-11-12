import { describe, expect, it } from "vitest";
import type { Bet } from "@/features/bets/types";
import { PASS } from "@/features/bets/types";
import { reducer } from "./reducer";
import type { AppState } from "./state";

const ME = "me";

function world(over: Partial<AppState> = {}): AppState {
  return {
    meId: ME,
    bets: [],
    messages: [],
    tables: [
      { id: "t", name: "Home", emoji: "🏠", memberIds: [ME, "a", "b"], hostIds: [ME] },
      {
        id: "src",
        name: "Source",
        emoji: "📣",
        memberIds: ["a"],
        hostIds: ["a"],
        quickPicksOn: true,
        shareCode: "ABCDEF",
      },
    ],
    games: [],
    follows: {},
    invitePrefs: {},
    tableInvites: [],
    reactions: {},
    users: [
      { id: ME, username: "pro_pat", hue: 1 },
      { id: "a", username: "avam", hue: 2 },
      { id: "b", username: "benl", hue: 3 },
    ],
    blocks: {},
    idBase: "x",
    seq: 0,
    ...over,
  };
}

const bet = (over: Partial<Bet> = {}): Bet => ({
  id: "b1",
  tableId: "t",
  proposerId: ME,
  modeId: "either_or",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wager: 1,
  durationSec: 60,
  closesInSec: 60,
  status: "active",
  participants: [{ userId: ME, selection: PASS }],
  createdAt: "now",
  ...over,
});

describe("store reducer", () => {
  it("keeps a member while bets are unsettled, then permits removal after settlement", () => {
    const action = { type: "removeMember" as const, tableId: "t", userId: "a", at: "1:00" };
    const active = world({ bets: [bet()] });
    expect(reducer(active, action)).toBe(active);
    const unsettled = world({ bets: [bet({ status: "resolved" })] });
    expect(reducer(unsettled, action)).toBe(unsettled);
    const settled = world({ bets: [bet({ status: "resolved", settled: true })] });
    expect(reducer(settled, action).tables[0]?.memberIds).not.toContain("a");
  });

  it("is pure: the same action on the same state gives the same messages and ids", () => {
    const s = world();
    const a = reducer(s, { type: "chat", tableId: "t", authorId: ME, text: "hi", at: "1:00" });
    const b = reducer(s, { type: "chat", tableId: "t", authorId: ME, text: "hi", at: "1:00" });
    expect(a).toEqual(b);
    const twice = reducer(a, { type: "chat", tableId: "t", authorId: "a", text: "yo", at: "1:01" });
    expect(new Set(twice.messages.map((m) => m.id)).size).toBe(2);
  });

  it("ignores a pick that isn't one of the bet's choices", () => {
    const s = world({ bets: [bet()] });
    const next = reducer(s, { type: "pick", betId: "b1", userId: "a", selection: "Maybe" });
    expect(next).toBe(s);
  });

  it("only the proposer's chosen settlers can call a U2Pick", () => {
    const custom = bet({ modeId: "u2pick", status: "pending", settledBy: "hosts" });
    const s = world({
      bets: [
        {
          ...custom,
          participants: [
            { userId: "a", selection: "Yes" },
            { userId: "b", selection: "No" },
          ],
        },
      ],
    });
    expect(
      reducer(s, { type: "settle", betId: "b1", winningChoice: "Yes", actorId: "a", at: "t" }),
    ).toBe(s);
    const called = reducer(s, {
      type: "settle",
      betId: "b1",
      winningChoice: "Yes",
      actorId: ME,
      at: "t",
    });
    expect(called.bets[0]!.status).toBe("resolved");
  });

  it("game bets can't be called by a person; they settle from the game result", () => {
    const s = world({
      bets: [
        bet({
          status: "pending",
          participants: [
            { userId: "a", selection: "Yes" },
            { userId: "b", selection: "No" },
          ],
        }),
      ],
    });
    expect(
      reducer(s, { type: "settle", betId: "b1", winningChoice: "Yes", actorId: ME, at: "t" }),
    ).toBe(s);
    const r = reducer(s, { type: "gameResult", betId: "b1", winningChoice: "Yes", at: "t" });
    expect(r.bets[0]!.deltas).toEqual({ a: 1, b: -1 });
  });

  it("locks a bet at zero and posts the lock line with the given time", () => {
    const s = world({ bets: [bet({ closesInSec: 1 })] });
    const next = reducer(s, { type: "tick", at: "7:42 PM" });
    expect(next.bets[0]).toMatchObject({ status: "pending", lockedAt: "7:42 PM" });
    expect(next.messages.at(-1)).toMatchObject({ kind: "system", at: "7:42 PM" });
  });

  it("fixes the leftover-cent rule on a bet when it locks", () => {
    const s = world({ bets: [bet({ closesInSec: 1 })] });
    s.tables[0] = { ...s.tables[0]!, remainderMode: "proposer" };
    const next = reducer(s, { type: "tick", at: "t" });
    expect(next.bets[0]!.remainder?.mode).toBe("proposer");
  });

  it("hosts can't change leftover cents while a bet takes picks", () => {
    const s = world({ bets: [bet({ status: "active", closesInSec: 30 })] });
    expect(reducer(s, { type: "setRemainderMode", tableId: "t", mode: "random", at: "t" })).toBe(s);
    const quiet = world();
    const next = reducer(quiet, { type: "setRemainderMode", tableId: "t", mode: "random", at: "t" });
    expect(next.tables[0]!.remainderMode).toBe("random");
  });

  it("turning on Quick Picks always shares the table with a code", () => {
    const next = reducer(world(), {
      type: "setQuickPicks",
      tableId: "t",
      on: true,
      code: "QWERTY",
      at: "t",
    });
    expect(next.tables[0]!.shareCode).toBe("QWERTY");
  });

  it("a Quick Pick source's new bet posts a copy at a subscribed table", () => {
    const s = world();
    const tables = s.tables.map((t) =>
      t.id === "t" ? { ...t, quickPicksOn: true, subscribedTo: ["src"] } : t,
    );
    const next = reducer(
      { ...s, tables },
      { type: "propose", bet: bet({ id: "q1", tableId: "src", proposerId: "a" }), at: "t" },
    );
    expect(next.bets.map((b) => b.id)).toEqual(["q1", "q1@t"]);
  });

  it("blocking someone removes follows both ways", () => {
    const s = world({ follows: { [ME]: ["a"], a: [ME] } });
    const next = reducer(s, { type: "block", from: ME, to: "a", on: true });
    expect(next.follows).toEqual({ [ME]: [], a: [] });
  });

  it("only a host can settle points, and it marks the table's results settled", () => {
    const done = bet({ status: "resolved", deltas: { a: 1, b: -1 } });
    const s = world({ bets: [done] });
    const next = reducer(s, { type: "settlePoints", tableId: "t", at: "t" });
    expect(next.bets[0]!.settled).toBe(true);
    expect(next.messages.at(-1)).toMatchObject({ kind: "settlement", hostId: ME });
    const notHost = { ...s, meId: "a" };
    expect(reducer(notHost, { type: "settlePoints", tableId: "t", at: "t" })).toBe(notHost);
  });
});

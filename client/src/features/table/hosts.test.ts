import { describe, expect, it } from "vitest";
import type { Table } from "@/features/table/types";
import type { Bet } from "@/features/bets/types";
import {
  canAddMember,
  canDeleteTable,
  canProposeBet,
  canProposeMode,
  canRemoveMember,
  canSetPermissions,
  canToggleHost,
  clearLimits,
  hasPermission,
  isHost,
  removalBlockedByBets,
  setPermission,
} from "./hosts";

const table: Table = {
  id: "t",
  name: "T",
  emoji: "🏈",
  memberIds: ["me", "a", "b"],
  hostIds: ["me"],
};

describe("host rules", () => {
  it("only hosts get host powers", () => {
    expect(isHost(table, "me")).toBe(true);
    expect(isHost(table, "a")).toBe(false);
    expect(canRemoveMember(table, "a", "b")).toBe(false);
    expect(canAddMember(table, "a", "z")).toBe(false);
  });

  it("a host can remove others but not themselves", () => {
    expect(canRemoveMember(table, "me", "a")).toBe(true);
    expect(canRemoveMember(table, "me", "me")).toBe(false);
    expect(canRemoveMember({ ...table, hostIds: ["me", "a"] }, "me", "a")).toBe(false);
  });

  it("blocks removal for live or unsettled points bets, but not settled or 4fun results", () => {
    const bet = (status: Bet["status"], extra: Partial<Bet> = {}) =>
      ({ tableId: "t", status, ...extra }) as Bet;
    expect(removalBlockedByBets(table, [bet("active", { forFun: true })])).toBe(true);
    expect(removalBlockedByBets(table, [bet("pending")])).toBe(true);
    expect(removalBlockedByBets(table, [bet("resolved")])).toBe(true);
    expect(removalBlockedByBets(table, [bet("resolved", { forFun: true })])).toBe(false);
    expect(removalBlockedByBets(table, [bet("resolved", { settled: true })])).toBe(false);
    expect(
      removalBlockedByBets(table, [bet("washed"), bet("active", { tableId: "elsewhere" })]),
    ).toBe(false);
  });

  it("the last host can't be demoted; with two hosts either can step down", () => {
    expect(canToggleHost(table, "me", "me")).toBe(false);
    expect(canToggleHost(table, "me", "a")).toBe(true);
    const two = { ...table, hostIds: ["me", "a"] };
    expect(canToggleHost(two, "me", "me")).toBe(true);
    expect(canToggleHost(two, "me", "a")).toBe(true);
  });

  it("everyone can start bets until a host limits bets to hosts", () => {
    expect(canProposeBet(table, "a")).toBe(true);
    const locked = { ...table, hostsOnlyBets: true };
    expect(canProposeBet(locked, "a")).toBe(false);
    expect(canProposeBet(locked, "me")).toBe(true);
    expect(canProposeBet(table, "z")).toBe(false);
  });

  it("turning off U2Pick blocks custom bets for everyone but leaves other modes alone", () => {
    expect(canProposeMode(table, "a", "u2pick")).toBe(true);
    const off = { ...table, u2pickOff: true };
    expect(canProposeMode(off, "a", "u2pick")).toBe(false);
    expect(canProposeMode(off, "me", "u2pick")).toBe(false);
    expect(canProposeMode(off, "a", "either_or")).toBe(true);
  });

  it("hosts can only add people who aren't already members", () => {
    expect(canAddMember(table, "me", "z")).toBe(true);
    expect(canAddMember(table, "me", "a")).toBe(false);
  });
});

describe("member permissions", () => {
  it("members can do everything until a host turns something off", () => {
    for (const p of ["bets", "text", "react", "gifs"] as const)
      expect(hasPermission(table, "a", p)).toBe(true);
    const muted = setPermission(table, "a", "text", false);
    expect(hasPermission(muted, "a", "text")).toBe(false);
    expect(hasPermission(muted, "a", "gifs")).toBe(true);
    expect(hasPermission(muted, "b", "text")).toBe(true);
  });

  it("turning a permission back on removes the limit", () => {
    const off = setPermission(table, "a", "react", false);
    expect(setPermission(off, "a", "react", true).limits).toEqual({});
  });

  it("no bets permission means they can't start bets either", () => {
    const off = setPermission(table, "a", "bets", false);
    expect(canProposeBet(off, "a")).toBe(false);
  });

  it("hosts can't be limited and only hosts can limit", () => {
    const two = { ...table, hostIds: ["me", "a"] };
    expect(canSetPermissions(two, "me", "a")).toBe(false);
    expect(canSetPermissions(table, "me", "me")).toBe(false);
    expect(canSetPermissions(table, "a", "b")).toBe(false);
    expect(canSetPermissions(table, "me", "a")).toBe(true);
    const limited = { ...setPermission(table, "a", "text", false), hostIds: ["me", "a"] };
    expect(hasPermission(limited, "a", "text")).toBe(true);
  });

  it("clearing limits drops them for that member only", () => {
    const t = setPermission(setPermission(table, "a", "text", false), "b", "gifs", false);
    expect(clearLimits(t, "a").limits).toEqual({ b: ["gifs"] });
  });

  it("people who left have no permissions", () => {
    expect(hasPermission(table, "z", "text")).toBe(false);
  });
});

describe("hosts-only bets beats a member's bet permission", () => {
  it("a member with bets on still can't start a bet when only hosts can, but can pick", () => {
    const locked = { ...table, hostsOnlyBets: true };
    expect(hasPermission(locked, "a", "bets")).toBe(true);
    expect(canProposeBet(locked, "a")).toBe(false);
    expect(canProposeBet(locked, "me")).toBe(true);
  });
});

describe("canDeleteTable", () => {
  const t = { id: "t1", hostIds: ["h"], memberIds: ["h", "m"] } as unknown as Table;
  const bet = (status: string) => ({ tableId: "t1", status }) as unknown as Bet;
  it("lets a host delete when all bets are resolved or washed", () => {
    expect(canDeleteTable(t, "h", [bet("resolved"), bet("washed")])).toBe(true);
  });
  it("blocks deleting while a bet is active", () => {
    expect(canDeleteTable(t, "h", [bet("active")])).toBe(false);
  });
  it("blocks deleting while a bet is pending", () => {
    expect(canDeleteTable(t, "h", [bet("pending")])).toBe(false);
  });
  it("never lets a non-host delete", () => {
    expect(canDeleteTable(t, "m", [])).toBe(false);
  });
});

import { describe, expect, it } from "vitest";
import { bet } from "@/test/api-fixtures";
import type { Bet } from "./api";
import { inPlayTableIds } from "./in-play";

const picked = bet as Bet;
const other = "123e4567-e89b-42d3-a456-426614174099";

describe("in play", () => {
  it("counts only tables where you picked a choice in an active bet", () => {
    const noGuess: Bet = { ...picked, tableId: other, myPick: null, myParticipation: "no_guess" };
    const pending: Bet = { ...picked, tableId: other, status: "PENDING" };
    expect([...inPlayTableIds([picked, noGuess, pending])]).toEqual([picked.tableId]);
    expect(inPlayTableIds([]).size).toBe(0);
  });
});

import { describe, expect, it } from "vitest";
import {
  DEFAULT_RULES,
  allowedMoves,
  cardFace,
  dealerWorstCase,
  myTurn,
  rulesFromConfig,
  rulesProblem,
  type BlackjackHand,
  type BlackjackSeat,
} from "./blackjack";

const seat = (cards: string[][], extra: Partial<BlackjackSeat> = {}): BlackjackSeat => ({
  userId: "123e4567-e89b-42d3-a456-426614174001",
  seat: 1,
  betCents: 100,
  hands: cards.map((c) => ({
    cards: c,
    total: 0,
    soft: false,
    stakeCents: 100,
    doubled: false,
    done: false,
  })),
  ...extra,
});

describe("blackjack rules", () => {
  it("keeps every number inside its minimum and maximum", () => {
    expect(rulesProblem(DEFAULT_RULES)).toBeNull();
    expect(rulesProblem({ ...DEFAULT_RULES, decks: 9 })).toMatch(/decks/);
    expect(rulesProblem({ ...DEFAULT_RULES, maxSplits: 6 })).toMatch(/splits/);
    expect(rulesProblem({ ...DEFAULT_RULES, seats: 0 })).toMatch(/seats/);
    expect(rulesProblem({ ...DEFAULT_RULES, minBetCents: 500, maxBetCents: 400 })).toMatch(
      /minimum/,
    );
    expect(rulesProblem({ ...DEFAULT_RULES, decisionSec: 5 })).toMatch(/decision/);
  });

  it("shows the dealer's worst case: every seat at the max, every split, every double", () => {
    expect(dealerWorstCase(DEFAULT_RULES)).toBe(7 * 1000 * 2 * 4);
    expect(dealerWorstCase({ ...DEFAULT_RULES, seats: 2, maxSplits: 0, maxBetCents: 500 })).toBe(
      2000,
    );
  });

  it("reads rules back from a bet's config", () => {
    expect(
      rulesFromConfig({
        decks: 2,
        minBetCents: 50,
        maxBetCents: 300,
        maxSplits: 5,
        dealerHitsSoft17: "yes",
        blackjackPays: "6:5",
        seats: 3,
        doubleOn: "9-11",
        doubleAfterSplit: "no",
        insurance: "yes",
        turnOrder: "seats",
        joinSec: 60,
        decisionSec: 20,
      }),
    ).toEqual({
      decks: 2,
      minBetCents: 50,
      maxBetCents: 300,
      maxSplits: 5,
      dealerHitsSoft17: true,
      blackjackPays: "6:5",
      seats: 3,
      doubleOn: "9-11",
      doubleAfterSplit: false,
      insurance: true,
      turnOrder: "seats",
      joinSec: 60,
      decisionSec: 20,
    });
  });
});

describe("blackjack moves", () => {
  it("allows a split on a pair until the limit, and doubles under the rules", () => {
    expect(allowedMoves(DEFAULT_RULES, seat([["8S", "8D"]]))).toEqual({
      double: true,
      split: true,
    });
    expect(allowedMoves({ ...DEFAULT_RULES, maxSplits: 0 }, seat([["8S", "8D"]])).split).toBe(
      false,
    );
    expect(allowedMoves(DEFAULT_RULES, seat([["KS", "QD"]])).split).toBe(true);
    expect(allowedMoves(DEFAULT_RULES, seat([["KS", "7D", "2C"]]))).toEqual({
      double: false,
      split: false,
    });
    const nineToEleven = { ...DEFAULT_RULES, doubleOn: "9-11" as const };
    expect(allowedMoves(nineToEleven, seat([["5S", "5D"]])).double).toBe(true);
    expect(allowedMoves(nineToEleven, seat([["KS", "7D"]])).double).toBe(false);
  });

  it("knows when it's your turn", () => {
    const mine = seat([["8S", "8D"]], { seat: 2 });
    const hand = (phase: BlackjackHand["phase"], turnSeat?: number): BlackjackHand => ({
      phase,
      dealer: ["9H", "??"],
      seats: [mine],
      ...(turnSeat === undefined ? {} : { turnSeat }),
    });
    expect(myTurn(hand("play"), mine)).toBe(true);
    expect(myTurn(hand("play", 1), mine)).toBe(false);
    expect(myTurn(hand("play", 2), mine)).toBe(true);
    expect(myTurn(hand("insurance"), { ...mine, insuranceOpen: true })).toBe(true);
    expect(myTurn(hand("done"), mine)).toBe(false);
  });

  it("draws card faces", () => {
    expect(cardFace("TH")).toEqual({ rank: "10", suit: "♥", red: true });
    expect(cardFace("AS")).toEqual({ rank: "A", suit: "♠", red: false });
    expect(cardFace("??")).toBeNull();
  });
});

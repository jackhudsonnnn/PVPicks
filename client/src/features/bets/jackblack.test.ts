import { cardFace } from "@/features/bets/cards";
import { describe, expect, it } from "vitest";
import {
  DEFAULT_RULES,
  deciding,
  playersOf,
  rulesFromConfig,
  rulesProblem,
  stillToPlay,
  worstCase,
  type JackblackGame,
} from "./jackblack";

const A = "123e4567-e89b-42d3-a456-426614174001";
const B = "123e4567-e89b-42d3-a456-426614174002";
const C = "123e4567-e89b-42d3-a456-426614174003";

const hand = (cards: string[], current = false) => ({
  cards,
  total: 0,
  soft: false,
  stakeCents: 100,
  doubled: false,
  done: false,
  current,
});

describe("jackblack rules", () => {
  it("keeps every number inside its minimum and maximum", () => {
    expect(rulesProblem(DEFAULT_RULES)).toBeNull();
    expect(rulesProblem({ ...DEFAULT_RULES, maxSplits: 4 })).toMatch(/splits/);
    expect(rulesProblem({ ...DEFAULT_RULES, decisionSec: 5 })).toMatch(/decision/);
  });

  it("matches the server's worst case", () => {
    // Doubling and one split: 4.00 a hand sitting, 4.00 a seat dealing, one other player.
    expect(worstCase(DEFAULT_RULES, 100, 2)).toBe(800);
    // No doubling or splits: a natural (1.50) is the most a dealer pays a seat.
    expect(worstCase({ ...DEFAULT_RULES, doubling: false, maxSplits: 0 }, 100, 2)).toBe(250);
    expect(
      worstCase({ ...DEFAULT_RULES, doubling: false, maxSplits: 0, blackjackPays: "6:5" }, 105, 2),
    ).toBe(105 + 126);
  });

  it("reads rules back from a bet's config", () => {
    expect(
      rulesFromConfig({
        doubling: "no",
        maxSplits: 0,
        blackjackPays: "6:5",
        decisionSec: 15,
        minPlayers: 3,
        maxPlayers: 5,
      }),
    ).toEqual({
      doubling: false,
      maxSplits: 0,
      blackjackPays: "6:5",
      decisionSec: 15,
      minPlayers: 3,
      maxPlayers: 5,
    });
    expect(rulesProblem({ ...DEFAULT_RULES, minPlayers: 5, maxPlayers: 4 })).toBe(
      "Players are 2 to 7.",
    );
  });
});

describe("jackblack game", () => {
  const game: JackblackGame = {
    phase: "play",
    round: 1,
    rounds: 3,
    order: [A, B, C],
    dealerId: A,
    dealer: ["9H", "??"],
    // B plays first (left of the dealer), C waits for their turn.
    turnId: B,
    seats: [
      { userId: B, hands: [hand(["TS", "2C"], true)] },
      { userId: C, hands: [hand(["TH", "3C"])] },
    ],
  };

  it("knows whose turn it is and who still has a hand to play", () => {
    expect(deciding(game, B)).toBe(true);
    expect(deciding(game, C)).toBe(false);
    expect(deciding(game, A)).toBe(false);
    expect(deciding({ ...game, phase: "dealer" }, B)).toBe(false);
    expect(stillToPlay(game.seats![1]!)).toBe(true);
    expect(stillToPlay({ userId: C, hands: [{ ...hand(["TH", "3C"]), done: true }] })).toBe(false);
    expect(playersOf(game)).toEqual([A, B, C]);
    expect(playersOf({ phase: "waiting", players: [A] })).toEqual([A]);
  });

  it("shows card faces", () => {
    expect(cardFace("TH")).toEqual({ rank: "10", suit: "♥", red: true });
    expect(cardFace("AS")).toEqual({ rank: "A", suit: "♠", red: false });
    expect(cardFace("??")).toBeNull();
  });
});

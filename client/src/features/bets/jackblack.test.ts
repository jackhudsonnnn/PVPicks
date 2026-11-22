import { describe, expect, it } from "vitest";
import {
  DEFAULT_RULES,
  cardFace,
  deciding,
  playersOf,
  rulesFromConfig,
  rulesProblem,
  undecided,
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
    expect(rulesProblem({ ...DEFAULT_RULES, decks: 9 })).toMatch(/decks/);
    expect(rulesProblem({ ...DEFAULT_RULES, rotations: 4 })).toMatch(/rotations/);
    expect(rulesProblem({ ...DEFAULT_RULES, maxSplits: 4 })).toMatch(/splits/);
    expect(rulesProblem({ ...DEFAULT_RULES, decisionSec: 5 })).toMatch(/decision/);
  });

  it("matches the server's worst case", () => {
    // Doubling and one split: 4.00 a hand sitting, 4.00 a seat dealing, one other player.
    expect(worstCase(DEFAULT_RULES, 100, 2)).toBe(800);
    expect(worstCase({ ...DEFAULT_RULES, rotations: 2 }, 100, 3)).toBe(3200);
    // No doubling or splits: a natural (1.50) is the most a dealer pays a seat.
    expect(worstCase({ ...DEFAULT_RULES, doubling: false, maxSplits: 0 }, 100, 2)).toBe(250);
    expect(
      worstCase({ ...DEFAULT_RULES, doubling: false, maxSplits: 0, blackjackPays: "6:5" }, 105, 2),
    ).toBe(105 + 126);
  });

  it("reads rules back from a bet's config", () => {
    expect(
      rulesFromConfig({
        decks: 2,
        rotations: 3,
        doubling: "no",
        maxSplits: 0,
        blackjackPays: "6:5",
        decisionSec: 15,
      }),
    ).toEqual({
      decks: 2,
      rotations: 3,
      doubling: false,
      maxSplits: 0,
      blackjackPays: "6:5",
      decisionSec: 15,
    });
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
    seats: [
      { userId: B, hands: [hand(["TS", "2C"], true)], deciding: false, choice: "hit" },
      { userId: C, hands: [hand(["TH", "3C"])], deciding: true },
    ],
  };

  it("knows who still has to choose, never what anyone chose", () => {
    expect(deciding(game, B)).toBe(false);
    expect(deciding(game, C)).toBe(true);
    expect(deciding(game, A)).toBe(false);
    expect(undecided(game)).toBe(1);
    expect(playersOf(game)).toEqual([A, B, C]);
    expect(playersOf({ phase: "waiting", players: [A] })).toEqual([A]);
  });

  it("shows card faces", () => {
    expect(cardFace("TH")).toEqual({ rank: "10", suit: "♥", red: true });
    expect(cardFace("AS")).toEqual({ rank: "A", suit: "♠", red: false });
    expect(cardFace("??")).toBeNull();
  });
});

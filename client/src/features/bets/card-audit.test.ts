import { describe, expect, it } from "vitest";
import { auditGame, auditHoldem, commitmentOf, shoeOf } from "./card-audit";
import type { JackblackGame } from "./jackblack";

const SEED = "00".repeat(32);

describe("card shuffle audit", () => {
  it("replays the server's shuffle and commitment exactly", async () => {
    // The same values JackblackIntegrationTest pins for CardShuffle.java.
    const shoe = await shoeOf(2, SEED);
    expect(shoe).toHaveLength(104);
    expect(shoe.slice(0, 6)).toEqual(["5D", "8D", "QS", "7H", "KH", "AH"]);
    expect(new Set(shoe).size).toBe(52);
    expect(await commitmentOf(1, 2, SEED)).toBe(
      "791e338ebc7be61ca9e96400bb0e3a463e937598712287692cf8cfa5434f7728",
    );
  });

  it("checks each revealed round against its fingerprint and the cards dealt", async () => {
    const commitment = await commitmentOf(1, 2, SEED);
    const hand = (cards: string[]) => ({
      cards,
      total: 0,
      soft: false,
      stakeCents: 100,
      doubled: false,
      done: true,
      current: false,
    });
    const game = (dealer: string[], seat: string[], decks = 2): JackblackGame => ({
      phase: "done",
      shuffles: [{ round: 1, commitment, decks, seed: SEED }],
      history: [
        {
          number: 1,
          dealerId: "123e4567-e89b-42d3-a456-426614174000",
          dealer,
          dealerTotal: 0,
          dealerDeltaCents: 0,
          seats: [{ userId: "123e4567-e89b-42d3-a456-426614174001", hands: [hand(seat)] }],
        },
      ],
    });
    // The top four cards (5D 8D QS 7H), dealt to the seat and the dealer.
    await expect(auditGame(game(["8D", "7H"], ["5D", "QS"]))).resolves.toEqual([
      { round: 1, decks: 2, committed: true, dealt: true },
    ]);
    await expect(auditGame(game(["8D", "7H"], ["5D", "KS"]))).resolves.toEqual([
      { round: 1, decks: 2, committed: true, dealt: false },
    ]);
    await expect(auditGame(game(["8D", "7H"], ["5D", "QS"], 3))).resolves.toEqual([
      { round: 1, decks: 3, committed: false, dealt: false },
    ]);
    await expect(
      auditGame({ phase: "play", shuffles: [{ round: 1, commitment }] }),
    ).resolves.toBeNull();
  });

  it("checks a finished Hold'em game's hands: everyone's hole cards and the board", async () => {
    // One deck: 4D AD 5S 7D dealt as hole cards, then the flop KD 3C JC, turn 3S, river 9D.
    expect((await shoeOf(1, SEED)).slice(0, 9)).toEqual([
      "4D",
      "AD",
      "5S",
      "7D",
      "KD",
      "3C",
      "JC",
      "3S",
      "9D",
    ]);
    const commitment = await commitmentOf(1, 1, SEED);
    expect(commitment).toBe("f64448d1771ead01b28123ff45ef839dcde2a6bfa361ecd29b082a33e9ce88b9");
    const a = "123e4567-e89b-42d3-a456-426614174000";
    const b = "123e4567-e89b-42d3-a456-426614174001";
    const game = (holes: Record<string, string[]>, phase = "done") => ({
      phase,
      shuffles: [
        { round: 1, commitment, decks: 1, seed: SEED },
        // A hand that never dealt (the game ended early) isn't checked.
        { round: 2, commitment: "0".repeat(64), decks: 1, seed: SEED },
      ],
      history: [{ number: 1, board: ["KD", "3C", "JC", "3S", "9D"], holes }],
    });
    await expect(auditHoldem(game({ [a]: ["4D", "5S"], [b]: ["AD", "7D"] }))).resolves.toEqual([
      { round: 1, decks: 1, committed: true, dealt: true },
    ]);
    await expect(auditHoldem(game({ [a]: ["4D", "5S"], [b]: ["AS", "7D"] }))).resolves.toEqual([
      { round: 1, decks: 1, committed: true, dealt: false },
    ]);
    await expect(auditHoldem(game({}, "play"))).resolves.toBeNull();
  });
});

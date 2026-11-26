import type { JackblackGame } from "./jackblack";

/**
 * Checks a finished card game's decks in the browser (domain rules, "Shuffle audit"): Jackblack's shoe each round,
 * Hold'em's deck each hand. Each one's commitment was on the ticket from the deal; once the game is over the server
 * reveals its deck count and seed. This replays the server's shuffle (`CardShuffle.java`, the same steps) and checks
 * that:
 *
 * 1. the revealed deck count and seed hash to the commitment shown from the start, and
 * 2. the cards dealt that round are exactly the top of that deck.
 */

const VERSION = "cards-v1";
const TWO_32 = 2 ** 32;
const encoder = new TextEncoder();

async function sha256(text: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(text)));
}

const hex = (bytes: Uint8Array) =>
  Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");

export async function commitmentOf(round: number, decks: number, seed: string) {
  return hex(await sha256(`${VERSION}|${round}|${decks}|${seed}`));
}

/** The shoe for a secret, top card first; pass 0 is the shoe, later passes continue it. */
export async function shoeOf(decks: number, seed: string, pass = 0): Promise<string[]> {
  const shoe: string[] = [];
  for (let d = 0; d < decks; d++)
    for (const suit of "SHDC") for (const rank of "A23456789TJQK") shoe.push(rank + suit);
  const prefix = `${VERSION}|${seed}|${pass}|`;
  let block = 0;
  let words: Uint8Array = new Uint8Array(0);
  let at = 0;
  const next = async () => {
    if (at >= words.length) {
      words = await sha256(prefix + block++);
      at = 0;
    }
    const word =
      ((words[at]! << 24) | (words[at + 1]! << 16) | (words[at + 2]! << 8) | words[at + 3]!) >>> 0;
    at += 4;
    return word;
  };
  for (let i = shoe.length - 1; i > 0; i--) {
    const bound = i + 1;
    const limit = TWO_32 - (TWO_32 % bound);
    let word = await next();
    while (word >= limit) word = await next();
    const j = word % bound;
    [shoe[i], shoe[j]] = [shoe[j]!, shoe[i]!];
  }
  return shoe;
}

type RoundAudit = {
  round: number;
  decks: number;
  /** The revealed secret hashes to the commitment shown from the deal. */
  committed: boolean;
  /** The round's cards are exactly the top of its shoe. */
  dealt: boolean;
};

const sorted = (cards: string[]) => [...cards].sort();

/** A round's (or hand's) shuffle as the server shows it: its commitment, and its secret once the game is over. */
export type Shuffle = {
  round: number;
  commitment: string;
  decks?: number | undefined;
  seed?: string | undefined;
};

/**
 * Audits every round of a finished game against `dealt`, each round's cards in any order (null if it never dealt);
 * null while the secrets aren't revealed.
 */
async function auditRounds(
  shuffles: Shuffle[],
  done: boolean,
  dealt: (round: number) => string[] | null,
): Promise<RoundAudit[] | null> {
  if (!done || shuffles.some((s) => s.seed === undefined)) return null;
  const revealed = shuffles.flatMap((s) =>
    s.seed !== undefined && s.decks !== undefined
      ? [{ round: s.round, commitment: s.commitment, decks: s.decks, seed: s.seed }]
      : [],
  );
  return Promise.all(
    revealed.map(async (s) => {
      const cards = dealt(s.round);
      const shoe: string[] = [];
      for (let pass = 0; shoe.length < (cards?.length ?? 0); pass++)
        shoe.push(...(await shoeOf(s.decks, s.seed, pass)));
      const top = shoe.slice(0, cards?.length ?? 0);
      return {
        round: s.round,
        decks: s.decks,
        committed: (await commitmentOf(s.round, s.decks, s.seed)) === s.commitment,
        dealt: !!cards && sorted(cards).join() === sorted(top).join(),
      };
    }),
  );
}

/** A finished Jackblack game: each round's dealer and seat cards. */
export function auditGame(game: JackblackGame): Promise<RoundAudit[] | null> {
  return auditRounds(game.shuffles ?? [], game.phase === "done", (number) => {
    const round = game.history?.find((r) => r.number === number);
    return round
      ? [...round.dealer, ...round.seats.flatMap((seat) => seat.hands.flatMap((h) => h.cards))]
      : null;
  });
}

/**
 * A finished Hold'em game: each hand's hole cards (everyone's, revealed once it's over) and board. Hands that were
 * never dealt (the game ended early) have no cards to check.
 */
export function auditHoldem(game: {
  phase: string;
  shuffles?: Shuffle[] | undefined;
  history?:
    { number: number; board: string[]; holes?: Record<string, string[]> | undefined }[] | undefined;
}): Promise<RoundAudit[] | null> {
  const dealtHands = new Set((game.history ?? []).map((h) => h.number));
  return auditRounds(
    (game.shuffles ?? []).filter((s) => dealtHands.has(s.round)),
    game.phase === "done",
    (number) => {
      const hand = game.history?.find((h) => h.number === number);
      return hand && hand.holes ? [...Object.values(hand.holes).flat(), ...hand.board] : null;
    },
  );
}

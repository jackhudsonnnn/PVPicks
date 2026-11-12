import { PASS, type Bet, type Participant } from "./types";

/** A pick is one of the bet's choices, or PASS to sit out. */
export const isValidPick = (bet: Pick<Bet, "choices">, selection: string) =>
  selection === PASS || bet.choices.includes(selection);

/**
 * Picking a choice joins the bet (no separate Join step). Clearing your guess
 * keeps you in the bet on PASS, so you're still a participant but not in the pot.
 */
export function applyPick(
  participants: Participant[],
  userId: string,
  selection: string,
): Participant[] {
  const mine = participants.find((p) => p.userId === userId);
  if (!mine) return selection === PASS ? participants : [...participants, { userId, selection }];
  return participants.map((p) => (p.userId === userId ? { ...p, selection } : p));
}

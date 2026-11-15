import type { Bet } from "./api";
import { statusOf, type BetStatus } from "./statuses";
import type { Table } from "@/features/tables";

/** What a bet ticket shows, derived only from the server's projection of the bet for this viewer. */

export const ticketStatus = (bet: Bet): BetStatus => statusOf(bet.status);

/** Whole seconds until picks lock, never negative. The server locks the bet; the client only counts down. */
export function secondsLeft(bet: Bet, nowMs: number) {
  return Math.max(0, Math.ceil((Date.parse(bet.locksAt) - nowMs) / 1000));
}

/** "1:30" under an hour, "2:05:09" under a day, then "3d 4h". */
export function formatClock(seconds: number) {
  const pad = (n: number) => n.toString().padStart(2, "0");
  if (seconds >= 86_400)
    return `${Math.floor(seconds / 86_400)}d ${Math.floor((seconds % 86_400) / 3600)}h`;
  if (seconds >= 3600)
    return `${Math.floor(seconds / 3600)}:${pad(Math.floor((seconds % 3600) / 60))}:${pad(seconds % 60)}`;
  return `${Math.floor(seconds / 60)}:${pad(seconds % 60)}`;
}

interface ChoiceTally {
  choice: string;
  count: number;
  /** Share of the people in the pot, rounded to a whole percent. */
  pct: number;
}

/** Picks per choice once picks are revealed (after lock); null while picks are still private. */
export function choiceTallies(bet: Bet): ChoiceTally[] | null {
  if (!bet.picks) return null;
  const total = bet.picks.length;
  return bet.choices.map((choice) => {
    const count = bet.picks!.filter((pick) => pick.choice === choice).length;
    return { choice, count, pct: total ? Math.round((count / total) * 100) : 0 };
  });
}

/** Everyone who joined: pickers first, then No guess, in the server's participation order. */
export function participantIds(bet: Bet): string[] | null {
  if (!bet.picks || !bet.noGuessUserIds) return null;
  return [...bet.picks.map((pick) => pick.userId), ...bet.noGuessUserIds];
}

/** Points in the pot: one wager per person with a pick. Only known after lock. */
export function potCents(bet: Bet): number | null {
  if (!bet.picks) return null;
  return bet.forFun ? 0 : bet.picks.length * bet.wagerCents;
}

/**
 * The viewer's result on a resolved bet: their delta, 0 for a pick that broke even, or null when they had no
 * pick. The server omits zero deltas.
 */
export function myResultCents(bet: Bet, userId: string): number | null {
  if (bet.status !== "SETTLED" || bet.forFun || bet.myParticipation !== "picked") return null;
  return bet.deltasCents?.[userId] ?? 0;
}

/** The line under an active ticket's choices, from the viewer's point of view. */
export function activeHint(bet: Bet, canBet: boolean, wager: string) {
  if (!canBet) return "A host turned off betting for you at this table.";
  const stake = bet.forFun
    ? "4fun: no points at stake."
    : `${wager} pts goes in the pot when picks lock.`;
  if (bet.myParticipation === "not_joined") return `Tap a choice to join. ${stake}`;
  if (bet.myParticipation === "no_guess")
    return "You have not made a guess. Tap a choice to get in the pot.";
  return "Picks stay hidden until the timer runs out. Tap again to clear your guess.";
}

/** Why the viewer can't propose any bet at this table, or null when they can. The server rechecks. */
export function proposeBlock(table: Table, userId: string): string | null {
  if (table.disabledPermissions[userId]?.includes("bets"))
    return "A host turned off betting for you at this table.";
  if (table.hostsOnlyBets && !table.hostIds.includes(userId))
    return "Only hosts can start bets at this table.";
  return null;
}

/** Why U2Pick in particular is off at this table, or null. Sports modes don't depend on it. */
export const u2pickBlock = (table: Table) =>
  table.u2pickEnabled ? null : "A host turned off U2Pick at this table.";

import type { Bet } from "./api";

export type BetStatus = "active" | "pending" | "resolved" | "washed";

interface StatusInfo {
  /** The badge's style key. */
  id: BetStatus;
  label: string;
  /** One line for the Tickets tab. */
  hint: string;
  /** The How to play guide's longer explanation. */
  explainer: string;
}

/**
 * Every bet status's name and wording, in lifecycle order. The badge, Tickets tabs, bet details, seat chips,
 * chat cards and the guide all read from here, so a status reads the same everywhere.
 */
export const STATUSES: Record<Bet["status"], StatusInfo> = {
  ACTIVE: {
    id: "active",
    label: "Active",
    hint: "Timer running, you can still change your pick.",
    explainer:
      "The ticket is open with a countdown. Tap a choice to join, and change or clear your pick until time runs out. Picks stay hidden.",
  },
  PENDING: {
    id: "pending",
    label: "Pending",
    hint: "Wagers are in the pot. Waiting on the result.",
    explainer:
      "Picks lock and are revealed. Everyone with a guess puts the wager into the pot. Now we wait for the play.",
  },
  SETTLED: {
    id: "resolved",
    label: "Resolved",
    hint: "A winning choice was called + payouts.",
    explainer:
      "A winning choice emerges. Losers' pot is split evenly among the winners when a host settles the table's points.",
  },
  WASHED: {
    id: "washed",
    label: "Washed",
    hint: "No contest, everyone got their points back.",
    explainer:
      "Everyone picked the same side, nobody picked the winner, or the outcome wasn't one of the choices. Points go back.",
  },
};

/** Lifecycle order, for tabs and the guide. */
export const STATUS_ORDER: readonly Bet["status"][] = ["ACTIVE", "PENDING", "SETTLED", "WASHED"];

export const statusOf = (status: Bet["status"]): BetStatus => STATUSES[status].id;

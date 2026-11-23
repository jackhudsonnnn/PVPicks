import {
  Club,
  Crown,
  Flame,
  PenLine,
  Scale,
  Spade,
  Split,
  Swords,
  Target,
  Wand2,
  type LucideIcon,
} from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { listModes } from "./api";
import type { ModeId } from "./types";

/**
 * A bet mode as the guide and proposal sheet describe it. The server owns each mode's fields, questions,
 * resolution and whether it can be proposed (`useModeAvailability`).
 */
export interface ModeDef {
  id: ModeId;
  name: string;
  tagline: string;
  description: string;
  icon: LucideIcon;
  /** U2Pick: no game; the proposer writes the question and options and picks who settles it. */
  custom?: boolean;
  /**
   * A card game played on its ticket, not picked (Jackblack, Hold'em): no game; players join during a join window,
   * the wager is the ante or big blind, and the game deals when the window closes.
   */
  cardGame?: "jackblack" | "holdem";
}

export const MODES: ModeDef[] = [
  {
    id: "either_or",
    name: "Either Or",
    tagline: "Who racks up more?",
    description:
      "Pick which of two players will accumulate more of a stat, like passing yards or points, by the deadline.",
    icon: Swords,
  },
  {
    id: "king_of_the_hill",
    name: "King Of The Hill",
    tagline: "First to the milestone",
    description:
      "A race: which of two players reaches the target first? If the game ends before either does, Neither wins.",
    icon: Crown,
  },
  {
    id: "choose_their_fate",
    name: "Choose Their Fate",
    tagline: "How does this one end?",
    description:
      "Predict how the current drive (NFL) or possession (NBA) ends, from touchdowns to empty trips.",
    icon: Wand2,
  },
  {
    id: "scorcerer",
    name: "Scorcerer",
    tagline: "Call the next score",
    description:
      "Which team scores next, and how: a touchdown or field goal (NFL), or a 3 or a 2 (NBA)? Or no more scores before the end. Safeties and free throws don't count.",
    icon: Flame,
  },
  {
    id: "total_disaster",
    name: "Total Disaster",
    tagline: "Over / Under the total",
    description:
      "The proposer sets a line for the combined final score. Will the game finish over or under it?",
    icon: Target,
  },
  {
    id: "give_and_take",
    name: "Give And Take",
    tagline: "Who covers the spread?",
    description:
      "A point handicap is applied to the home team. Pick which side covers, margin matters, not just the winner.",
    icon: Scale,
  },
  {
    id: "prop_hunt",
    name: "Prop Hunt",
    tagline: "One player, one line",
    description:
      "Will a single athlete finish over or under a stat line set by the proposer? Classic player prop, peer-to-peer.",
    icon: Split,
  },
  {
    id: "u2pick",
    name: "U2Pick",
    tagline: "Your bet, your options",
    description:
      "Bet on anything. Write the question and the options, then choose who gets to call the result: anyone, people not in it, hosts, or just you.",
    icon: PenLine,
    custom: true,
  },
  {
    id: "jackblack",
    name: "Jackblack",
    tagline: "Everyone takes a turn dealing",
    description:
      "Blackjack where the deal goes round the table: everyone who joins banks a round against the rest, in a random order. Choices are made together, and new cards come out only once everyone has chosen.",
    icon: Spade,
    cardGame: "jackblack",
  },
  {
    id: "holdem",
    name: "Hold'em",
    tagline: "No-limit poker, once round the table",
    description:
      "No-limit Texas hold'em: one hand each with the button moving round, small and big blinds, 100 big blinds to buy in and one rebuy. You see only your own cards; the board, the bets and the pot are out in the open.",
    icon: Club,
    cardGame: "holdem",
  },
];

/**
 * Which modes can be proposed, from `GET /modes`. Undefined while loading, so the sheet shows modes as unavailable
 * until the server says otherwise.
 */
export function useModeAvailability() {
  return useQuery({
    queryKey: ["modes"],
    queryFn: ({ signal }) => listModes(signal),
    staleTime: 5 * 60_000,
    retry: false,
  }).data;
}

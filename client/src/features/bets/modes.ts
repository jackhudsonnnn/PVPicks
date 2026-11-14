import {
  Crown,
  Flame,
  PenLine,
  Scale,
  Split,
  Swords,
  Target,
  Wand2,
  type LucideIcon,
} from "lucide-react";
import type { ModeId } from "./types";

/** A bet mode as the guide describes it. The server owns each mode's fields, questions and resolution. */
export interface ModeDef {
  id: ModeId;
  name: string;
  tagline: string;
  description: string;
  icon: LucideIcon;
  /** U2Pick: no game; the proposer writes the question and options and picks who settles it. */
  custom?: boolean;
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
      "Predict the method of the next scoring play. Keeps things tense between big moments.",
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
];

/** Types and settings shared by the Host and About panels. */
import { type ComponentType } from "react";
import {
  ChartBar,
  Images,
  Lock,
  MessageSquareText,
  PenLine,
  SmilePlus,
  Ticket,
} from "lucide-react";
import { type Bet, type BetActions } from "@/features/bets";
import type { Table, Permission, RemainderRule, TableBalance } from "@/features/tables/api";
import { type User } from "@/features/people";
import type { useTableActions } from "@/features/tables/actions";

type TableActions = ReturnType<typeof useTableActions>;

export interface People {
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

export interface PanelProps extends People {
  table: Table;
  bets: Bet[] | undefined;
  balances: TableBalance[] | undefined;
  tableActions: TableActions;
  betActions: BetActions;
  className?: string;
}

export const PERMISSIONS: {
  id: Permission;
  label: string;
  description: string;
  icon: ComponentType<{ className?: string }>;
}[] = [
  { id: "bets", label: "Bets", description: "Start bets and make picks.", icon: Ticket },
  { id: "text", label: "Messages", description: "Send text messages.", icon: MessageSquareText },
  {
    id: "react",
    label: "Reactions",
    description: "React to messages and throw tomatoes.",
    icon: SmilePlus,
  },
  { id: "gifs", label: "GIFs", description: "Send GIFs.", icon: Images },
  { id: "polls", label: "Polls", description: "Start polls in the chat.", icon: ChartBar },
];

export const REMAINDER: { id: RemainderRule; label: string; description: string }[] = [
  { id: "host", label: "Host", description: "Leftover cents go to a host who's in the pot." },
  { id: "proposer", label: "Proposer", description: "Leftover cents go to whoever proposed it." },
  {
    id: "earliest",
    label: "Earliest pick",
    description: "Leftover cents go to the first winner to pick.",
  },
  { id: "random", label: "Random", description: "Leftover cents go to a random winner." },
];

export const SWITCHES = [
  {
    id: "hostsOnlyBets",
    label: "Only hosts start bets",
    description: "Members can still pick on any bet.",
    icon: Lock,
    isOn: (t: Table) => t.hostsOnlyBets,
  },
  {
    id: "u2pickEnabled",
    label: "Allow U2Pick bets",
    description: "Custom bets with your own question and options.",
    icon: PenLine,
    isOn: (t: Table) => t.u2pickEnabled,
  },
  {
    id: "pollsEnabled",
    label: "Allow polls",
    description: "Members can start polls in the chat. Existing polls stay open.",
    icon: ChartBar,
    isOn: (t: Table) => t.pollsEnabled,
  },
] as const;

export const busy = (actions: { busy: boolean; pending: unknown; storageError: unknown }) =>
  actions.busy || !!actions.pending || !!actions.storageError;

/** Whether the table has bets that block deleting it or removing members. */
export function hasOpenBets(bets: Bet[] | undefined) {
  return (bets ?? []).some(
    (bet) =>
      bet.status === "ACTIVE" ||
      bet.status === "PENDING" ||
      (bet.status === "SETTLED" && !bet.pointsSettled),
  );
}

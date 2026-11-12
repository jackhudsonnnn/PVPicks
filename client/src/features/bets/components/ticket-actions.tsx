import { type ReactNode } from "react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

import { FlaskConical, Gavel, ShieldAlert } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useAppStore, useGetUser, useMe } from "@/features/store";

import { type Bet } from "../types";

import { canSettleCustom, eligibleSettlers, settlerRule } from "../u2pick";

/** U2Pick: people the proposer allowed call the result; everyone else waits on them. */
export function CallItMenu({ bet }: { bet: Bet }) {
  const me = useMe();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const table = state.tables.find((t) => t.id === bet.tableId);
  if (!table) return null;
  if (!canSettleCustom(bet, table, me)) {
    const who = eligibleSettlers(bet, table);
    const names =
      who.length === 1
        ? getUser(who[0]!).username.split(" ")[0]
        : settlerRule(bet.settledBy ?? "hosts").label.toLowerCase();
    return <span className="text-right text-ticket-muted">Waiting on {names}</span>;
  }
  return (
    <OutcomeMenu
      bet={bet}
      icon={<Gavel className="size-3.5" />}
      label="Call it"
      washLabel="None of these (wash)"
      onPick={(c) => actions.settle(bet.id, c)}
    />
  );
}

/** Sample data: stands in for the sports feed reporting the game's result. */
export function SimulateMenu({ bet }: { bet: Bet }) {
  const { actions } = useAppStore();
  return (
    <OutcomeMenu
      bet={bet}
      icon={<FlaskConical className="size-3.5" />}
      label="Simulate"
      washLabel="Something else (wash)"
      onPick={(c) => actions.simulateResult(bet.id, c)}
    />
  );
}

/** "What happened?" menu: one of the bet's choices, or a wash (null). */
function OutcomeMenu({
  bet,
  icon,
  label,
  washLabel,
  onPick,
}: {
  bet: Bet;
  icon: ReactNode;
  label: string;
  washLabel: string;
  onPick: (choice: string | null) => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" variant="ticketGhost" className="h-7 gap-1.5 px-2">
          {icon} {label}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>What happened?</DropdownMenuLabel>
        {bet.choices.map((c) => (
          <DropdownMenuItem key={c} onClick={() => onPick(c)}>
            {c}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => onPick(null)}>{washLabel}</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Quick Pick copies: a host here can wash the source's call if it looks wrong. */
export function OverrideButton({ bet }: { bet: Bet }) {
  const { actions } = useAppStore();
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button size="sm" variant="ticketGhost" className="h-7 w-full gap-1.5">
          <ShieldAlert className="size-3.5" />
          {bet.status === "pending" ? "Override: wash it" : "Result looks wrong? Wash it"}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Wash this Quick Pick?</AlertDialogTitle>
          <AlertDialogDescription>
            Only at this table. Everyone's wager comes back and {bet.quickPick?.sourceName}'s call
            no longer counts here. This can't be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={() => actions.overrideQuickPick(bet.id)}>
            Wash it
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

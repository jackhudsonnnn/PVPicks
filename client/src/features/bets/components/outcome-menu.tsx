/** Calling or overriding a ticket's result, and who settles a U2Pick. */
import { useState } from "react";
import { Gavel, ShieldAlert } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { settlerRule } from "@/features/bets/u2pick";
import type { Bet } from "@/features/bets/api";
import type { BetActions } from "./bet-controls";

export function SettlerLabel({ bet, proposer }: { bet: Bet; proposer: string | undefined }) {
  const rule = bet.settledBy ?? "hosts";
  if (rule === "me")
    return <>{proposer === "You" ? "you call it" : `${proposer ?? "…"} calls it`}</>;
  // "Anyone calls it", but "Hosts call it" and "Non-participants call it".
  return (
    <>
      {settlerRule(rule).label} {rule === "anyone" ? "calls" : "call"} it
    </>
  );
}

export function SettlerWho({ bet, proposer }: { bet: Bet; proposer: string | undefined }) {
  const rule = bet.settledBy ?? "hosts";
  if (rule === "me") return <>{proposer ?? "the proposer"}</>;
  return <>{settlerRule(rule).label.toLowerCase()}</>;
}

/**
 * "What happened?" menu: one of the bet's choices or a wash, confirmed before it's sent. U2Pick settlers call the
 * result once; with {@code override}, a host sets or corrects a sports result until points are settled.
 */
export function OutcomeMenu({
  bet,
  tableId,
  actions,
  disabled,
  override = false,
}: {
  bet: Bet;
  tableId: string;
  actions: BetActions;
  disabled: boolean;
  override?: boolean;
}) {
  // undefined: nothing chosen; null: wash.
  const [choice, setChoice] = useState<string | null | undefined>(undefined);
  const resolved = bet.status === "SETTLED" || bet.status === "WASHED";
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button size="sm" variant="ticketGhost" className="h-7 gap-1.5 px-2" disabled={disabled}>
            {override ? <ShieldAlert className="size-3.5" /> : <Gavel className="size-3.5" />}
            {override ? (resolved ? "Change result" : "Override") : "Call it"}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuLabel>{override ? "Set the result" : "What happened?"}</DropdownMenuLabel>
          {bet.choices.map((c) => (
            <DropdownMenuItem key={c} onSelect={() => setChoice(c)}>
              {c}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={() => setChoice(null)}>
            {override ? "Wash it" : "None of these (wash)"}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <AlertDialog open={choice !== undefined} onOpenChange={(o) => !o && setChoice(undefined)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {choice === null
                ? "Wash this bet?"
                : override
                  ? `Set “${choice}” as the result?`
                  : `Call “${choice}” as the result?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {override
                ? choice === null
                  ? "Everyone's wager comes back. The table sees that a host washed it. You can change it again until points are settled."
                  : "This replaces the feed's result. The table sees that a host set it. You can change it again until points are settled."
                : choice === null
                  ? "Everyone's wager comes back. This can't be undone."
                  : "Points move between players who picked. This can't be undone."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (choice !== undefined)
                  actions.start({
                    kind: override ? "override" : "resolve",
                    tableId,
                    betId: bet.id,
                    winningChoice: choice,
                  });
                setChoice(undefined);
              }}
            >
              Confirm
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

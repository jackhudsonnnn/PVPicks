/** The Host panel's points settlement: who pays whom, and settling the table. */
import { HandCoins } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { SettingsSection } from "@/components/settings";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { awaitingPointsSettlement } from "@/features/bets";
import { PersonAvatar } from "@/features/people";
import { PanelProps, busy } from "./host-shared";
import { PersonLabel } from "./host-panel";
import { LoadingList } from "@/components/loading";

/** Each member's points that the next settlement moves; everyone pays or is paid through the host. */
export function SettlementPreview({ table, balances, ...people }: PanelProps) {
  const rows = (balances ?? []).filter((b) => b.settleCents !== 0n);
  if (!balances) return <LoadingList label="Loading balances" rows={3} rowClassName="h-6" />;
  if (rows.length === 0) return <p className="text-sm text-muted-foreground">Everyone is at 0.</p>;
  return (
    <ul className="space-y-1.5">
      {rows.map((row) => (
        <li key={row.userId} className="flex items-center gap-2.5 text-sm">
          <PersonAvatar
            id={row.userId}
            size="xs"
            user={people.user}
            expire={people.expire}
            queryKey={people.peopleKey}
          />
          <span className="min-w-0 flex-1 break-words">
            <PersonLabel id={row.userId} {...people} />{" "}
            <span className="text-muted-foreground">
              {row.settleCents > 0n
                ? row.userId === people.user.id
                  ? "get paid"
                  : "gets paid"
                : row.userId === people.user.id
                  ? "pay"
                  : "pays"}
            </span>
          </span>
          <span
            className={cn("scoreboard text-base", row.settleCents > 0n ? "text-win" : "text-loss")}
          >
            {formatCents(row.settleCents > 0n ? row.settleCents : -row.settleCents)}
          </span>
        </li>
      ))}
      <li className="pt-1 text-xs text-muted-foreground">
        {table.hostIds.length === 1 ? "Through the host." : "Through the host who settles."}
      </li>
    </ul>
  );
}

export function SettleSection(props: PanelProps) {
  const { table, bets, betActions } = props;
  const waiting = awaitingPointsSettlement(bets ?? []);
  return (
    <SettingsSection
      title="Settle points"
      description="Pay everyone out through you so each settle balance goes back to 0. Pending bets keep running. Zero-sum, so you end up with your own result."
    >
      <div className="space-y-3 px-4 py-3.5 sm:px-5">
        <SettlementPreview {...props} />
        <p className="text-xs text-muted-foreground">
          {waiting === 0
            ? "No resolved bets are waiting."
            : `${waiting} resolved ${waiting === 1 ? "bet is" : "bets are"} waiting for points settlement.`}
        </p>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button size="sm" disabled={waiting === 0 || busy(betActions)}>
              <HandCoins /> Settle table points
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Settle {table.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                Everyone's settle balance here goes back to 0. A message in chat shows who pays
                whom, and everyone gets receipts on their Friends page. This can't be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => betActions.start({ kind: "settle-points", tableId: table.id })}
              >
                Confirm
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </SettingsSection>
  );
}

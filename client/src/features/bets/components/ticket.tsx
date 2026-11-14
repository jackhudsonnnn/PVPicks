import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Check, EyeOff, Gavel, Lock, ShieldAlert, Trophy, Undo2 } from "lucide-react";
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
import { MODES } from "@/features/bets/modes";
import { StatusBadge } from "./status-badge";
import { settlerRule } from "@/features/bets/u2pick";
import { formatCents } from "@/lib/money";
import { timeLabel } from "@/lib/time";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import type { Table } from "@/features/tables";
import { type User, AvatarStack, usePerson } from "@/features/people";
import { BetDetailsDialog } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { canCallResult } from "@/features/bets/bet-results";
import { useNow } from "@/lib/use-now";
import { useTicker, tickerStatus } from "@/features/sports";
import { isScoreMode, scoreLine } from "@/features/bets/sports-model";
import {
  activeHint,
  choiceTallies,
  formatClock,
  myResultCents,
  participantIds,
  potCents,
  secondsLeft,
  ticketStatus,
} from "@/features/bets/ticket-model";

interface TicketProps {
  bet: Bet;
  table: Table;
  user: User;
  expire: () => void;
  /** Profile cache key shared by every name and avatar on the page. */
  peopleKey: readonly string[];
  className?: string;
}

/**
 * A bet as a ticket: tap a choice to pick (asks first), tap your pick again to clear it, call the result from the
 * locked ticket when your settler rule allows. Without `actions` it renders as a preview.
 */
export function BetTicket({
  bet,
  table,
  user,
  expire,
  peopleKey,
  className,
  actions,
}: TicketProps & { actions?: BetActions }) {
  const preview = !actions;
  const status = ticketStatus(bet);
  const now = useNow(status === "active" && !preview);
  const left = secondsLeft(bet, now);
  const mode = MODES.find((m) => m.id === bet.modeId)!;
  const Icon = mode.icon;
  const tallies = choiceTallies(bet);
  const pot = potCents(bet);
  const result = myResultCents(bet, user.id);
  const players = participantIds(bet);
  const canBet = !table.disabledPermissions[user.id]?.includes("bets");
  const blocked = !actions || actions.busy || !!actions.pending || !!actions.storageError;
  const canPick = status === "active" && canBet && left > 0 && !blocked;
  const proposer = usePerson(bet.proposerId, user, expire, peopleKey);
  const ticker = useTicker(user.id);
  const game = bet.gameId ? ticker.data?.find((g) => g.id === bet.gameId) : undefined;
  const sports = isScoreMode(bet.modeId);
  // Hosts have the final say on sports results until the table's points are settled.
  const canOverride = !!actions && sports && table.hostIds.includes(user.id) && !bet.pointsSettled;
  const wager = formatCents(bet.wagerCents);
  const [confirming, setConfirming] = useState<string | null>(null);

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <header className="flex h-19 items-center gap-3 px-4">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-display text-lg font-extrabold uppercase leading-none">{mode.name}</p>
          <p className="mt-1 line-clamp-2 break-words font-mono text-[11px] leading-tight text-ticket-muted">
            {bet.gameId ? (
              game ? (
                scoreLine(game, tickerStatus(game))
              ) : (
                "Game"
              )
            ) : (
              <>
                Custom · <SettlerLabel bet={bet} proposer={proposer?.name} />
              </>
            )}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <StatusBadge status={status} />
          {status === "active" && (
            <span
              className={cn(
                "scoreboard text-xl leading-none",
                left <= 15 ? "text-loss" : "text-ticket-foreground",
              )}
              aria-label="Time left to change your pick"
            >
              {left > 0 ? formatClock(left) : "Locking…"}
            </span>
          )}
        </div>
      </header>

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <h3 className="break-words font-display text-2xl font-bold leading-tight">
          {bet.question}
        </h3>
        <div className="-mt-2 flex items-center justify-between gap-2">
          <p className="min-w-0 text-xs text-ticket-muted">
            Proposed by {proposer?.name ?? "…"} in{" "}
            {preview ? (
              <span className="font-semibold text-ticket-foreground">{table.name}</span>
            ) : (
              <Link
                to="/tables/$tableId"
                params={{ tableId: table.id }}
                className="font-semibold text-ticket-foreground underline underline-offset-2"
              >
                {table.name}
              </Link>
            )}{" "}
            at {timeLabel(bet.createdAt)}
          </p>
          <BetDetailsDialog
            bet={bet}
            table={table}
            user={user}
            expire={expire}
            peopleKey={peopleKey}
            disabled={preview}
          />
        </div>
        {bet.forFun && (
          <div className="-mt-2 flex flex-wrap gap-1.5">
            <span className="rounded-full bg-ticket-foreground px-2 py-0.5 font-mono text-[10px] font-bold uppercase text-ticket">
              4fun
            </span>
          </div>
        )}

        <ul className="space-y-2" aria-label="Choices">
          {bet.choices.map((choice) => {
            const tally = tallies?.find((t) => t.choice === choice);
            const selected = bet.myPick === choice;
            const winner = status === "resolved" && bet.winningChoice === choice;
            return (
              <li key={choice}>
                <button
                  type="button"
                  disabled={!canPick}
                  aria-pressed={selected}
                  onClick={() =>
                    selected
                      ? actions?.start({ kind: "clear", tableId: table.id, betId: bet.id })
                      : setConfirming(choice)
                  }
                  className={cn(
                    "relative flex w-full items-center gap-3 overflow-hidden rounded-lg border px-3 py-2.5 text-left text-sm font-semibold transition-all",
                    "border-ticket-foreground/15 disabled:cursor-default",
                    canPick && "hover:border-ticket-foreground/60 active:scale-[0.99]",
                    selected && "border-ticket-foreground bg-ticket-foreground text-ticket",
                    winner && "border-status-resolved bg-status-resolved text-primary-foreground",
                  )}
                >
                  {tally && !selected && !winner && (
                    <span
                      className="absolute inset-y-0 left-0 bg-ticket-foreground/8"
                      style={{ width: `${tally.pct}%` }}
                    />
                  )}
                  <span
                    className={cn(
                      "relative flex size-5 shrink-0 items-center justify-center rounded-full border",
                      selected || winner ? "border-current" : "border-ticket-foreground/30",
                    )}
                  >
                    {winner ? (
                      <Trophy className="size-3" />
                    ) : (
                      selected && <Check className="size-3" />
                    )}
                  </span>
                  <span className="relative flex-1 break-words">{choice}</span>
                  {tally && (
                    <span className="relative font-mono text-[11px] opacity-80">
                      {tally.count} · {tally.pct}%
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>

        {status === "active" && (
          <p className="flex items-center gap-2 text-xs text-ticket-muted">
            <EyeOff className="size-3.5 shrink-0" />
            {activeHint(bet, canBet, wager)}
          </p>
        )}
        {status === "pending" && (
          <div className="flex items-center justify-between gap-2 rounded-lg bg-status-pending/20 px-3 py-2 text-xs font-medium">
            <span className="flex items-center gap-2">
              <Lock className="size-3.5" /> Locked ·{" "}
              {sports ? "waiting on the game" : "picks are in"}
            </span>
            {sports ? (
              canOverride ? (
                <OutcomeMenu
                  bet={bet}
                  tableId={table.id}
                  actions={actions!}
                  disabled={blocked}
                  override
                />
              ) : (
                <span className="text-right text-ticket-muted">Settles from the feed</span>
              )
            ) : actions && canCallResult(bet, table, user.id) ? (
              <OutcomeMenu bet={bet} tableId={table.id} actions={actions} disabled={blocked} />
            ) : (
              <span className="text-right text-ticket-muted">
                Waiting on <SettlerWho bet={bet} proposer={proposer?.name} />
              </span>
            )}
          </div>
        )}
        {status === "resolved" && result !== null && (
          <div
            className={cn(
              "flex items-center justify-between rounded-lg px-3 py-2",
              result > 0 ? "bg-win/20" : result < 0 ? "bg-loss/15" : "bg-ticket-foreground/8",
            )}
          >
            <span className="text-xs font-semibold uppercase tracking-wider">
              {result > 0 ? "You won" : result < 0 ? "You lost" : "Broke even"}
            </span>
            <span
              className={cn(
                "scoreboard text-2xl",
                result > 0 ? "text-win" : result < 0 ? "text-loss" : "text-ticket-foreground",
              )}
            >
              {formatCents(result, true)} pts
            </span>
          </div>
        )}
        {status === "washed" && (
          <div className="flex items-center gap-2 rounded-lg bg-status-washed/20 px-3 py-2 text-xs font-medium">
            <Undo2 className="size-3.5" /> {bet.washReason ?? "Washed"}, wagers returned
          </div>
        )}

        {bet.overriddenAt && (
          <p className="flex items-center gap-1.5 text-xs text-ticket-muted">
            <ShieldAlert className="size-3.5" /> Result set by a host
          </p>
        )}
        {canOverride && status !== "pending" && (
          <div className="flex justify-end">
            <OutcomeMenu
              bet={bet}
              tableId={table.id}
              actions={actions!}
              disabled={blocked}
              override
            />
          </div>
        )}

        <div className="flex items-center justify-between border-t border-ticket-foreground/10 pt-3">
          {players && players.length > 0 ? (
            preview ? (
              <AvatarStack ids={players} user={user} expire={expire} queryKey={peopleKey} />
            ) : (
              <Link
                to="/tables/$tableId"
                params={{ tableId: table.id }}
                search={{ tab: "peers" }}
                aria-label="See peers at this table"
                title="See peers at this table"
                className="rounded-full transition-opacity hover:opacity-80"
              >
                <AvatarStack ids={players} user={user} expire={expire} queryKey={peopleKey} />
              </Link>
            )
          ) : (
            <span className="text-xs text-ticket-muted">
              {status === "active" ? "Picks stay private until lock" : "Nobody joined"}
            </span>
          )}
          <dl className="flex gap-4 text-right font-mono text-[11px] uppercase tracking-wider text-ticket-muted">
            <div>
              <dt>Wager</dt>
              <dd className="scoreboard text-base text-ticket-foreground">
                {bet.forFun ? "4fun" : wager}
              </dd>
            </div>
            <div>
              <dt>{pot === null ? "Picks" : "Pot"}</dt>
              <dd className="scoreboard text-base text-ticket-foreground">
                {pot === null ? bet.pickCount : bet.forFun ? "–" : formatCents(pot)}
              </dd>
            </div>
          </dl>
        </div>
      </div>

      <AlertDialog open={confirming !== null} onOpenChange={(o) => !o && setConfirming(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {bet.myPick
                ? `Change your pick from ${bet.myPick} to ${confirming}?`
                : `Pick ${confirming}?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {bet.forFun
                ? "4fun: no points at stake."
                : `${wager} pts goes in the pot when picks lock.`}{" "}
              You can still change it until the timer runs out.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (confirming)
                  actions?.start({
                    kind: "pick",
                    tableId: table.id,
                    betId: bet.id,
                    choice: confirming,
                  });
                setConfirming(null);
              }}
            >
              Confirm
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </article>
  );
}

function SettlerLabel({ bet, proposer }: { bet: Bet; proposer: string | undefined }) {
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

function SettlerWho({ bet, proposer }: { bet: Bet; proposer: string | undefined }) {
  const rule = bet.settledBy ?? "hosts";
  if (rule === "me") return <>{proposer ?? "the proposer"}</>;
  return <>{settlerRule(rule).label.toLowerCase()}</>;
}

/**
 * "What happened?" menu: one of the bet's choices or a wash, confirmed before it's sent. U2Pick settlers call the
 * result once; with {@code override}, a host sets or corrects a sports result until points are settled.
 */
function OutcomeMenu({
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

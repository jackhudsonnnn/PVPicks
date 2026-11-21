import { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Check, EyeOff, Lock, ShieldAlert, Trophy, Undo2 } from "lucide-react";
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
import { MODES } from "@/features/bets/modes";
import { StatusBadge } from "./status-badge";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import { canBetAt, type Table, type TableSummary } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { BetDetailsDialog } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { useNow } from "@/lib/use-now";
import { useTicker, tickerStatus } from "@/features/sports";
import { isScoreMode, scoreLine } from "@/features/bets/sports-model";
import {
  activeHint,
  choiceTallies,
  formatClock,
  myResultCents,
  potCents,
  secondsLeft,
  ticketStatus,
} from "@/features/bets/ticket-model";
import { OutcomeMenu, SettlerLabel, SettlerWho } from "./outcome-menu";
import { BlackjackTicket } from "./blackjack-ticket";

interface TicketProps {
  bet: Bet;
  /** The whole table (on its page) or your summary of it (ticket lists): either has what a ticket needs. */
  table: Table | TableSummary;
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
export function BetTicket(props: TicketProps & { actions?: BetActions }) {
  // Blackjack is played on its ticket rather than picked.
  return props.bet.modeId === "blackjack" ? (
    <BlackjackTicket {...props} />
  ) : (
    <PickTicket {...props} />
  );
}

function PickTicket({
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
  const canBet = canBetAt(table, user.id);
  const blocked = !actions || actions.busy || !!actions.pending || !!actions.storageError;
  const canPick = status === "active" && canBet && left > 0 && !blocked;
  const proposer = usePerson(bet.proposerId, user, expire, peopleKey);
  const ticker = useTicker(user.id);
  const game = bet.gameId ? ticker.data?.find((g) => g.id === bet.gameId) : undefined;
  const sports = isScoreMode(bet.modeId);
  // The server says who may override (hosts, sports bets, before points settle) or call a result.
  const canOverride = !!actions && bet.viewerCanOverride;
  const wager = formatCents(bet.wagerCents);
  const [confirming, setConfirming] = useState<string | null>(null);

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <header className="flex min-h-19 items-center gap-3 py-3 pl-4 pr-10">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          {/* Status and clock sit beside the mode, keeping the top-right corner clear for a dialog's close button. */}
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <p className="font-display text-lg font-extrabold uppercase leading-none">
              {mode.name}
            </p>
            <StatusBadge status={status} />
            {status === "active" && (
              <span
                className={cn(
                  "scoreboard text-lg leading-none",
                  left <= 15 ? "text-loss" : "text-ticket-foreground",
                )}
                aria-label="Time left to change your pick"
              >
                {left > 0 ? formatClock(left) : "Locking…"}
              </span>
            )}
          </div>
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
      </header>

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <h3 className="break-words font-display text-2xl font-bold leading-tight">
          {bet.question}
        </h3>
        <div className="-mt-2 flex items-start justify-between gap-2">
          <div className="min-w-0 space-y-1.5 text-xs text-ticket-muted">
            <p className="flex min-w-0 items-center gap-1.5">
              <span className="shrink-0">Proposed by</span>
              {preview ? (
                <span className="flex min-w-0 items-center gap-1.5 font-semibold text-ticket-foreground">
                  <PersonAvatar
                    id={bet.proposerId}
                    size="xs"
                    user={user}
                    expire={expire}
                    queryKey={peopleKey}
                  />
                  <span className="truncate">{proposer?.name ?? "…"}</span>
                </span>
              ) : (
                <Link
                  to="/tables/$tableId"
                  params={{ tableId: table.id }}
                  search={{ profile: bet.proposerId }}
                  aria-label={`${proposer?.name ?? "Proposer"}'s profile`}
                  className="flex min-w-0 items-center gap-1.5 font-semibold text-ticket-foreground hover:underline"
                >
                  <PersonAvatar
                    id={bet.proposerId}
                    size="xs"
                    user={user}
                    expire={expire}
                    queryKey={peopleKey}
                  />
                  <span className="truncate">{proposer?.name ?? "…"}</span>
                </Link>
              )}
            </p>
          </div>

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
              <Lock className="size-3.5" /> Locked
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
            ) : actions && bet.viewerCanCall ? (
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

        <div className="flex items-center justify-between gap-3 border-t border-ticket-foreground/10 pt-3">
          <p className="flex min-w-0 items-center gap-1.5 text-xs">
            <span
              aria-hidden
              className="flex size-6 shrink-0 items-center justify-center rounded-full bg-ticket-foreground/10 text-sm"
            >
              {table.emoji}
            </span>
            {preview ? (
              <span className="truncate font-semibold text-ticket-foreground">{table.name}</span>
            ) : (
              <Link
                to="/tables/$tableId"
                params={{ tableId: table.id }}
                className="truncate font-semibold text-ticket-foreground underline underline-offset-2"
              >
                {table.name}
              </Link>
            )}
          </p>
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

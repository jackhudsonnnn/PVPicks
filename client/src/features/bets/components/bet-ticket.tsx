import { useState } from "react";
import { Link } from "@tanstack/react-router";
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
import { overtimeCounts } from "../overtime";
import { Check, EyeOff, Lock, Trophy, Undo2, Zap } from "lucide-react";
import { cn } from "@/lib/utils";

import { gameById, useAppStore, useGetUser, useMe, useNames } from "@/features/store";
import { AvatarStack } from "@/features/social/components/user-avatar";
import { getMode } from "../modes";
import { inThePot, projectedWin } from "../payout";
import { formatClock, formatPts } from "../ledger";
import { PASS, type Bet } from "../types";
import { StatusBadge } from "./status-badge";
import { BetDetailsDialog } from "./bet-details-dialog";
import { settlerRule } from "../u2pick";
import { hasPermission } from "@/features/table/hosts";
import { canOverride } from "@/features/table/quick-picks";
import { CallItMenu, OverrideButton, SimulateMenu } from "./ticket-actions";

export function BetTicket({
  bet,
  className,
  preview,
}: {
  bet: Bet;
  className?: string;
  /** Shows the ticket as it will look once sent: no picking, no links, Details disabled. */
  preview?: boolean;
}) {
  const me = useMe();
  const names = useNames();
  const getUser = useGetUser();
  const { state, actions } = useAppStore();
  const tableName = state.tables.find((t) => t.id === bet.tableId)?.name;
  const table = state.tables.find((t) => t.id === bet.tableId);
  const canBet = !!table && hasPermission(table, me, "bets");
  const mode = getMode(bet.modeId);
  const game = gameById(state, bet.gameId);
  const Icon = mode.icon;
  const mine = bet.participants.find((p) => p.userId === me);
  const revealed = bet.status !== "active";
  const players = inThePot(bet.participants);
  const pot = players.length * bet.wager;
  const myDelta = bet.deltas?.[me];
  const urgent = bet.status === "active" && bet.closesInSec <= 15;
  // Picking (first pick or a change) asks first; clearing your guess doesn't.
  const [confirming, setConfirming] = useState<string | null>(null);
  const current = mine?.selection ?? PASS;
  const extras = overtimeCounts(bet) === false ? ["No overtime"] : [];

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      {/* Stub */}
      <header className="flex h-19 items-center gap-3 px-4">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="font-display text-lg font-extrabold uppercase leading-none">{mode.name}</p>
          <p className="mt-1 line-clamp-2 break-words font-mono text-[11px] leading-tight text-ticket-muted">
            {game
              ? `${game.away.abbr} ${game.awayScore} @ ${game.home.abbr} ${game.homeScore} · ${game.clock}`
              : `Custom · ${settledByLabel(bet, names.name)}`}
          </p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <StatusBadge status={bet.status} />
          {bet.status === "active" && (
            <span
              className={cn(
                "scoreboard text-xl leading-none",
                urgent ? "text-loss" : "text-ticket-foreground",
              )}
              aria-label="Time left to change your pick"
            >
              {formatClock(bet.closesInSec)}
            </span>
          )}
        </div>
      </header>

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <div className="flex items-start justify-between gap-3">
          <h3 className="font-display text-2xl font-bold leading-tight">{bet.question}</h3>
        </div>
        <div className="-mt-2 flex items-center justify-between gap-2">
          <p className="min-w-0 text-xs text-ticket-muted">
            Proposed by {bet.proposerId === me ? "you" : getUser(bet.proposerId).username.split(" ")[0]}
            {tableName && (
              <>
                {" in "}
                {preview ? (
                  <span className="font-semibold text-ticket-foreground">{tableName}</span>
                ) : (
                  <Link
                    to="/tables/$tableId"
                    params={{ tableId: bet.tableId }}
                    search={{ bet: bet.id }}
                    className="font-semibold text-ticket-foreground underline underline-offset-2"
                  >
                    {tableName}
                  </Link>
                )}
              </>
            )}{" "}
            at {bet.createdAt}
            {bet.quickPick && (
              <span className="mt-0.5 flex items-center gap-1 font-semibold text-ticket-foreground">
                <Zap className="size-3" /> Quick Pick from {bet.quickPick.sourceName}
              </span>
            )}
          </p>
          <BetDetailsDialog bet={bet} disabled={preview} />
        </div>
        {(bet.forFun || extras.length > 0) && (
          <div className="-mt-2 flex flex-wrap gap-1.5">
            {bet.forFun && (
              <span className="rounded-full bg-ticket-foreground px-2 py-0.5 font-mono text-[10px] font-bold uppercase text-ticket">
                4fun
              </span>
            )}
            {extras.map((x) => (
              <span
                key={x}
                className="rounded-full border border-ticket-foreground/25 px-2 py-0.5 font-mono text-[10px] font-bold uppercase"
              >
                {x}
              </span>
            ))}
          </div>
        )}

        {/* Choices */}
        <ul className="space-y-2">
          {bet.choices.map((choice) => {
            const count = players.filter((p) => p.selection === choice).length;
            const pct = players.length ? Math.round((count / players.length) * 100) : 0;
            const selected = mine?.selection === choice;
            const winner = bet.winningChoice === choice && bet.status === "resolved";
            const canPick = bet.status === "active" && canBet && !preview;
            const proj = revealed ? projectedWin(bet.participants, bet.wager, choice) : 0;
            return (
              <li key={choice}>
                <button
                  type="button"
                  disabled={!canPick}
                  onClick={() => (selected ? actions.pick(bet.id, PASS) : setConfirming(choice))}
                  className={cn(
                    "relative flex w-full items-center gap-3 overflow-hidden rounded-lg border px-3 py-2.5 text-left text-sm font-semibold transition-all",
                    "border-ticket-foreground/15 disabled:cursor-default",
                    canPick && "hover:border-ticket-foreground/60 active:scale-[0.99]",
                    selected && "border-ticket-foreground bg-ticket-foreground text-ticket",
                    winner && "border-status-resolved bg-status-resolved text-primary-foreground",
                  )}
                >
                  {revealed && !selected && !winner && (
                    <span
                      className="absolute inset-y-0 left-0 bg-ticket-foreground/8"
                      style={{ width: `${pct}%` }}
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
                  <span className="relative flex-1">{choice}</span>
                  {revealed && (
                    <span className="relative flex items-center gap-2 font-mono text-[11px] opacity-80">
                      {bet.status === "pending" && proj > 0 && <span>+{formatPts(proj)}</span>}
                      <span>
                        {count} · {pct}%
                      </span>
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>

        {/* State line */}
        {bet.status === "active" && (
          <p className="flex items-center gap-2 text-xs text-ticket-muted">
            <EyeOff className="size-3.5 shrink-0" />
            {!canBet
              ? "A host turned off betting for you at this table."
              : !mine
                ? bet.forFun
                  ? "Tap a choice to join. 4fun: no points at stake."
                  : `Tap a choice to join. ${formatPts(bet.wager)} pts goes in the pot when picks lock.`
                : mine.selection === PASS
                  ? "You have not made a guess. Tap a choice to get in the pot."
                  : "Picks stay hidden until the timer runs out. Tap again to clear your guess."}
          </p>
        )}
        {bet.status === "pending" && (
          <div className="flex items-center justify-between gap-2 rounded-lg bg-status-pending/20 px-3 py-2 text-xs font-medium">
            <span className="flex items-center gap-2">
              <Lock className="size-3.5" /> Locked ·{" "}
              {mode.custom ? "picks are in" : "waiting on the play"}
            </span>
            {bet.quickPick ? (
              <span className="text-right text-ticket-muted">
                Waiting on {bet.quickPick.sourceName}
              </span>
            ) : mode.custom ? (
              <CallItMenu bet={bet} />
            ) : (
              <SimulateMenu bet={bet} />
            )}
          </div>
        )}
        {bet.status === "resolved" && myDelta !== undefined && (
          <div
            className={cn(
              "flex items-center justify-between rounded-lg px-3 py-2",
              myDelta > 0 ? "bg-win/20" : "bg-loss/15",
            )}
          >
            <span className="text-xs font-semibold uppercase tracking-wider">
              {myDelta > 0 ? "You won" : myDelta < 0 ? "You lost" : "Called"}
            </span>
            <span className={cn("scoreboard text-2xl", myDelta > 0 ? "text-win" : "text-loss")}>
              {formatPts(myDelta, true)} pts
            </span>
          </div>
        )}
        {bet.status === "washed" && (
          <div className="flex items-center gap-2 rounded-lg bg-status-washed/20 px-3 py-2 text-xs font-medium">
            <Undo2 className="size-3.5" /> {bet.washReason ?? "Washed"}, wagers returned
          </div>
        )}

        {table && canOverride(table, bet, me) && <OverrideButton bet={bet} />}

        {/* Footer */}
        <div className="flex items-center justify-between border-t border-ticket-foreground/10 pt-3">
          {preview ? (
            <AvatarStack ids={bet.participants.map((p) => p.userId)} />
          ) : (
            <Link
              to="/tables/$tableId"
              params={{ tableId: bet.tableId }}
              search={{ tab: "peers" }}
              aria-label="See peers at this table"
              title="See peers at this table"
              className="rounded-full transition-opacity hover:opacity-80"
            >
              <AvatarStack ids={bet.participants.map((p) => p.userId)} />
            </Link>
          )}
          <dl className="flex gap-4 text-right font-mono text-[11px] uppercase tracking-wider text-ticket-muted">
            <div>
              <dt>Wager</dt>
              <dd className="scoreboard text-base text-ticket-foreground">
                {bet.forFun ? "4fun" : formatPts(bet.wager)}
              </dd>
            </div>
            <div>
              <dt>{revealed ? "Pot" : "Joined"}</dt>
              <dd className="scoreboard text-base text-ticket-foreground">
                {revealed ? (bet.forFun ? "–" : formatPts(pot)) : bet.participants.length}
              </dd>
            </div>
          </dl>
        </div>
      </div>
      <AlertDialog open={confirming !== null} onOpenChange={(o) => !o && setConfirming(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {current === PASS
                ? `Pick ${confirming}?`
                : `Change your pick from ${current} to ${confirming}?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {bet.forFun
                ? "4fun: no points at stake."
                : `${formatPts(bet.wager)} pts goes in the pot when picks lock.`}{" "}
              You can still change it until the timer runs out.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (confirming) actions.pick(bet.id, confirming);
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

function settledByLabel(bet: Bet, first: (id: string) => string) {
  const rule = bet.settledBy ?? "hosts";
  if (rule === "me") {
    const who = first(bet.proposerId);
    return who === "You" ? "you call it" : `${who} calls it`;
  }
  return `${settlerRule(rule).label} call it`;
}

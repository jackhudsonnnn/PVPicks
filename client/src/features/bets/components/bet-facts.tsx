import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import { gameById, useAppStore, useNames } from "@/features/store";
import { inThePot } from "../payout";
import { formatClock, formatPts } from "../ledger";
import { sportOf } from "../sports";
import { overtimeCounts } from "../overtime";
import { betInfo } from "../info";
import { sampleSnapshot } from "../live-stats";
import type { Bet } from "../types";

const SETTLER_LABEL = {
  anyone: "Anyone at the table",
  non_participants: "Members not in the bet",
  hosts: "Hosts",
  me: "The proposer",
} as const;

function FactRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right font-medium">{children}</span>
    </div>
  );
}

export function FactSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-1">
      <h4 className="font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
        {title}
      </h4>
      <div className="divide-y rounded-lg border bg-surface px-3">{children}</div>
    </section>
  );
}

/** The "Bet" list shared by the Details popup and the proposal preview. */
export function BetFacts({ bet, onNavigate }: { bet: Bet; onNavigate?: (() => void) | undefined }) {
  const names = useNames();
  const { state } = useAppStore();
  const game = gameById(state, bet.gameId);
  const table = state.tables.find((t) => t.id === bet.tableId);
  const inPot = inThePot(bet.participants);
  const ot = overtimeCounts(bet);
  return (
    <FactSection title="Bet">
      <FactRow label="Table">
        {table ? (
          <Link
            to="/tables/$tableId"
            params={{ tableId: table.id }}
            search={{ bet: bet.id }}
            onClick={onNavigate}
            className="text-primary underline underline-offset-2"
          >
            {table.emoji} {table.name}
          </Link>
        ) : (
          "Unknown"
        )}
      </FactRow>
      {table && (
        <FactRow label="Tickets">
          <Link
            to="/tickets"
            search={{ table: bet.tableId }}
            onClick={onNavigate}
            className="text-primary underline underline-offset-2"
          >
            All {table.name} tickets
          </Link>
        </FactRow>
      )}
      <FactRow label="Proposed by">{names.name(bet.proposerId)}</FactRow>
      {bet.quickPick && (
        <FactRow label="Quick Pick from">
          {bet.quickPick.sourceName}
          {bet.quickPick.overridden ? " (overridden here)" : ", they call the result"}
        </FactRow>
      )}
      <FactRow label="Wager">
        {bet.forFun ? "4fun, no points" : `${formatPts(bet.wager)} pts`}
      </FactRow>
      {ot !== undefined && <FactRow label="Overtime">{ot ? "Counts" : "Doesn't count"}</FactRow>}
      <FactRow label="Pick window">{formatClock(bet.durationSec)}</FactRow>
      <FactRow label="Pot">{formatPts(inPot.length * bet.wager)} pts</FactRow>
      {game ? (
        <>
          <FactRow label="League">{sportOf(game).label}</FactRow>
          <FactRow label="Game">
            {game.away.abbr} {game.awayScore} @ {game.home.abbr} {game.homeScore} · {game.clock}
          </FactRow>
        </>
      ) : (
        <FactRow label="Called by">{SETTLER_LABEL[bet.settledBy ?? "anyone"]}</FactRow>
      )}
      <FactRow label="Choices">{bet.choices.join(", ")}</FactRow>
      {bet.winningChoice && <FactRow label="Outcome">{bet.winningChoice}</FactRow>}
      {bet.washReason && <FactRow label="Wash reason">{bet.washReason}</FactRow>}
    </FactSection>
  );
}

/** Mode-specific Info list. Live values are read on each render (each time the popup opens). */
export function BetInfoSection({ bet }: { bet: Bet }) {
  const names = useNames();
  const { state } = useAppStore();
  const game = gameById(state, bet.gameId);
  const rows = betInfo(
    bet,
    game,
    game ? sampleSnapshot(game) : undefined,
    names.name,
    bet.settledBy ? SETTLER_LABEL[bet.settledBy] : "",
  );
  return (
    <FactSection title="Info">
      {rows.map((r) => (
        <FactRow key={r.label} label={r.label}>
          {r.value}
        </FactRow>
      ))}
    </FactSection>
  );
}

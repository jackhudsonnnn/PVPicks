import { useState, type ReactNode } from "react";
import { Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { ScrollContainer } from "@/components/scroll-container";
import { MODES } from "@/features/bets/modes";
import { StatusBadge } from "@/features/bets/components/status-badge";
import { formatCents } from "@/lib/money";
import { dateTimeLabel } from "@/lib/time";
import { cn } from "@/lib/utils";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { PersonAvatar } from "./backend-avatar";
import { PersonName } from "./backend-person-name";
import { formatClock, potCents, ticketStatus } from "./ticket-model";

const SETTLER = {
  anyone: "Anyone at the table",
  non_participants: "Members not in the bet",
  hosts: "Hosts",
  me: "The proposer",
} as const;

const REMAINDER = {
  host: "A host",
  proposer: "The proposer",
  earliest: "Earliest pick",
  random: "Random",
} as const;

function FactRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="break-words text-right font-medium">{children}</span>
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

interface Props {
  bet: BackendBet;
  table: BackendTable;
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
}

/** The "Bet" facts, shared by the Details dialog and the proposal preview. */
export function BetFacts({ bet, table, user, expire, peopleKey }: Props) {
  const pot = potCents(bet);
  const windowSec = Math.round((Date.parse(bet.locksAt) - Date.parse(bet.createdAt)) / 1000);
  return (
    <FactSection title="Bet">
      <FactRow label="Table">
        {table.emoji} {table.name}
      </FactRow>
      <FactRow label="Proposed by">
        <PersonName id={bet.proposerId} user={user} expire={expire} queryKey={peopleKey} />
      </FactRow>
      <FactRow label="Wager">
        {bet.forFun ? "4fun, no points" : `${formatCents(bet.wagerCents)} pts`}
      </FactRow>
      <FactRow label="Pick window">{formatClock(Math.max(0, windowSec))}</FactRow>
      {pot !== null && !bet.forFun && <FactRow label="Pot">{formatCents(pot)} pts</FactRow>}
      <FactRow label="Called by">{SETTLER[bet.settledBy ?? "hosts"]}</FactRow>
      {bet.remainderRule && (
        <FactRow label="Leftover cents">{REMAINDER[bet.remainderRule]}</FactRow>
      )}
      <FactRow label="Choices">{bet.choices.join(", ")}</FactRow>
      {bet.winningChoice && <FactRow label="Outcome">{bet.winningChoice}</FactRow>}
      {bet.calledBy && (
        <FactRow label="Called">
          <PersonName id={bet.calledBy} user={user} expire={expire} queryKey={peopleKey} />
        </FactRow>
      )}
    </FactSection>
  );
}

export function BetDetailsDialog({ disabled, ...props }: Props & { disabled?: boolean }) {
  const { bet, user, expire, peopleKey } = props;
  const [open, setOpen] = useState(false);
  const status = ticketStatus(bet);
  const mode = MODES.find((m) => m.id === bet.modeId)!;
  const done = status === "resolved" || status === "washed";
  const steps: { label: string; at?: string | undefined; reached: boolean }[] = [
    { label: "Proposed", at: dateTimeLabel(bet.createdAt), reached: true },
    {
      label: "Picks locked",
      at: dateTimeLabel(bet.lockedAt ?? bet.locksAt),
      reached: status !== "active",
    },
    {
      label: status === "washed" ? "Washed" : "Resolved",
      at: bet.closedAt ? dateTimeLabel(bet.closedAt) : undefined,
      reached: done,
    },
  ];
  if (status === "resolved")
    steps.push({
      label: "Points settled",
      at: bet.settledAt ? dateTimeLabel(bet.settledAt) : undefined,
      reached: bet.pointsSettled,
    });

  const trigger = (
    <Button
      variant="ghost"
      size="sm"
      disabled={disabled}
      className="h-7 gap-1 px-2 text-xs text-ticket-muted hover:bg-ticket-foreground/10 hover:text-ticket-foreground"
    >
      <Info className="size-3.5" /> Details
    </Button>
  );
  if (disabled) return trigger;

  const rows = bet.picks
    ? [
        ...bet.picks.map((p) => ({ id: p.userId, pick: p.choice })),
        ...(bet.noGuessUserIds ?? []).map((id) => ({ id, pick: "No guess" })),
      ]
    : null;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent className="flex max-h-[85dvh] max-w-md flex-col gap-4 p-0">
        <DialogHeader className="px-5 pt-5 text-left">
          <div className="flex items-center gap-2">
            <StatusBadge status={status} />
            <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              {mode.name}
            </span>
          </div>
          <DialogTitle className="break-words text-2xl leading-tight">{bet.question}</DialogTitle>
          <DialogDescription>{mode.description}</DialogDescription>
        </DialogHeader>

        <ScrollContainer isVisible={false} className="min-h-0 flex-1 space-y-4 px-5 pb-5">
          <FactSection title="Timeline">
            <ol className="py-2">
              {steps.map((s, i) => (
                <li key={s.label} className="relative flex gap-3 pb-3 last:pb-0">
                  {i < steps.length - 1 && (
                    <span className="absolute left-[5px] top-3 h-full w-px bg-border" />
                  )}
                  <span
                    className={cn(
                      "relative mt-1 size-[11px] shrink-0 rounded-full border-2",
                      s.reached ? "border-primary bg-primary" : "border-muted-foreground",
                    )}
                  />
                  <div className="flex flex-1 justify-between gap-3 text-sm">
                    <span className={s.reached ? "font-semibold" : "text-muted-foreground"}>
                      {s.label}
                    </span>
                    <span className="font-mono text-xs text-muted-foreground">
                      {s.at ?? (s.reached ? "Earlier" : "Not yet")}
                    </span>
                  </div>
                </li>
              ))}
            </ol>
          </FactSection>

          <BetFacts {...props} />

          <FactSection title={rows ? `Players (${rows.length})` : `Picks (${bet.pickCount})`}>
            {rows ? (
              rows.length === 0 ? (
                <p className="py-1.5 text-sm text-muted-foreground">Nobody joined.</p>
              ) : (
                rows.map(({ id, pick }) => {
                  const d = bet.deltasCents?.[id];
                  return (
                    <div key={id} className="flex items-center gap-2 py-1.5 text-sm">
                      <PersonAvatar
                        id={id}
                        user={user}
                        expire={expire}
                        queryKey={peopleKey}
                        size="xs"
                      />
                      <span className="min-w-0 flex-1 break-words">
                        <PersonName id={id} user={user} expire={expire} queryKey={peopleKey} />
                      </span>
                      <span className="break-words text-muted-foreground">{pick}</span>
                      {d !== undefined && (
                        <span
                          className={cn(
                            "w-16 text-right font-mono text-xs font-bold",
                            d > 0 ? "text-win" : "text-loss",
                          )}
                        >
                          {formatCents(d, true)}
                        </span>
                      )}
                    </div>
                  );
                })
              )
            ) : (
              <p className="py-1.5 text-sm text-muted-foreground">
                {bet.myPick ? `Your pick: ${bet.myPick}. ` : ""}Other picks stay private until lock.
              </p>
            )}
          </FactSection>
          <p className="font-mono text-[10px] text-muted-foreground">Ticket #{bet.id}</p>
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

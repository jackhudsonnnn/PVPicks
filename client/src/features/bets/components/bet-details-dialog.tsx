import { Info } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
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
import { useMe, useNames } from "@/features/store";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { getMode } from "../modes";
import { formatClock, formatPts } from "../ledger";
import { PASS, type Bet } from "../types";
import { StatusBadge } from "./status-badge";
import { BetFacts, BetInfoSection, FactSection } from "./bet-facts";
import { useState } from "react";

export function BetDetailsDialog({ bet, disabled }: { bet: Bet; disabled?: boolean | undefined }) {
  const me = useMe();
  const names = useNames();
  const [open, setOpen] = useState(false);
  const mode = getMode(bet.modeId);
  const revealed = bet.status !== "active";
  const done = bet.status === "resolved" || bet.status === "washed";
  const earlier = "Earlier";

  const steps: { label: string; at?: string | undefined; reached: boolean }[] = [
    { label: "Proposed", at: bet.createdAt, reached: true },
    {
      label: "Picks locked",
      at:
        bet.status === "active" ? `in ${formatClock(bet.closesInSec)}` : (bet.lockedAt ?? earlier),
      reached: bet.status !== "active",
    },
    {
      label: bet.status === "washed" ? "Washed" : "Resolved",
      at: done ? (bet.closedAt ?? earlier) : undefined,
      reached: done,
    },
  ];
  if (bet.status === "resolved") {
    steps.push({
      label: "Points settled",
      at: bet.settled ? (bet.settledAt ?? earlier) : undefined,
      reached: !!bet.settled,
    });
  }

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

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent className="flex max-h-[85dvh] max-w-md flex-col gap-4 p-0">
        <DialogHeader className="px-5 pt-5 text-left">
          <div className="flex items-center gap-2">
            <StatusBadge status={bet.status} />
            <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
              {mode.name}
            </span>
          </div>
          <DialogTitle className="text-2xl leading-tight">{bet.question}</DialogTitle>
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
                      {s.at ?? "Not yet"}
                    </span>
                  </div>
                </li>
              ))}
            </ol>
          </FactSection>

          <BetFacts bet={bet} onNavigate={() => setOpen(false)} />
          <BetInfoSection bet={bet} />

          <FactSection title={`Players (${bet.participants.length})`}>
            {bet.participants.map((p) => {
              const d = bet.deltas?.[p.userId];
              const pick = !revealed
                ? p.selection === PASS
                  ? "No guess"
                  : p.userId === me
                    ? p.selection
                    : "Hidden"
                : p.selection === PASS
                  ? "No guess"
                  : p.selection;
              return (
                <div key={p.userId} className="flex items-center gap-2 py-1.5 text-sm">
                  <UserAvatar userId={p.userId} size="xs" />
                  <span className="min-w-0 flex-1 break-words">{names.name(p.userId)}</span>
                  <span className="break-words text-muted-foreground">{pick}</span>
                  {d !== undefined && (
                    <span
                      className={cn(
                        "w-14 text-right font-mono text-xs font-bold",
                        d > 0 ? "text-win" : d < 0 ? "text-loss" : "text-muted-foreground",
                      )}
                    >
                      {d > 0 ? "+" : ""}
                      {formatPts(d)}
                    </span>
                  )}
                </div>
              );
            })}
          </FactSection>
          <p className="font-mono text-[10px] text-muted-foreground">Ticket #{bet.id}</p>
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

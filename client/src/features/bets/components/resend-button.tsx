import { Link } from "@tanstack/react-router";
import { RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { Bet } from "@/features/bets/api";

/**
 * "Resend" on a finished (resolved or washed) ticket: opens its table's proposal sheet filled in with the same terms
 * (owner decision, 2026-11-24). Anyone who may propose there can; it's checked like any new bet, so terms that no
 * longer hold (a line already passed, a game that's over) are refused with the reason.
 */
export function ResendButton({ bet, tableId }: { bet: Bet; tableId: string }) {
  if (bet.status !== "SETTLED" && bet.status !== "WASHED") return null;
  return (
    <Button
      asChild
      variant="ghost"
      size="sm"
      className="h-7 gap-1 px-2 text-xs text-ticket-muted hover:bg-ticket-foreground/10 hover:text-ticket-foreground"
    >
      <Link to="/tables/$tableId" params={{ tableId }} search={{ resend: bet.id }}>
        <RotateCcw className="size-3.5" /> Resend
      </Link>
    </Button>
  );
}

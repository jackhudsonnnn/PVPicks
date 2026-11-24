import { Link } from "@tanstack/react-router";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { BetDetailsDialog } from "./bet-details";
import { ResendButton } from "./resend-button";

/**
 * The line under every ticket's perforation: who proposed it (opening their profile), then Resend (once it's
 * finished) and Details. Pick, sports and card-game tickets share it, so it sits in the same place on all of them.
 */
export function TicketByline({
  bet,
  table,
  user,
  expire,
  peopleKey,
  preview,
}: {
  bet: Bet;
  table: TableRef;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  preview: boolean;
}) {
  const proposer = usePerson(bet.proposerId, user, expire, peopleKey);
  const name = (
    <>
      <PersonAvatar
        id={bet.proposerId}
        size="xs"
        user={user}
        expire={expire}
        queryKey={peopleKey}
      />
      <span className="truncate">{proposer?.name ?? "…"}</span>
    </>
  );
  return (
    <div className="flex items-center justify-between gap-2">
      <p className="flex min-w-0 items-center gap-1.5 text-xs text-ticket-muted">
        <span className="shrink-0">Proposed by</span>
        {preview ? (
          <span className="flex min-w-0 items-center gap-1.5 font-semibold text-ticket-foreground">
            {name}
          </span>
        ) : (
          <Link
            to="/tables/$tableId"
            params={{ tableId: table.id }}
            search={{ profile: bet.proposerId }}
            aria-label={`${proposer?.name ?? "Proposer"}'s profile`}
            className="flex min-w-0 items-center gap-1.5 font-semibold text-ticket-foreground hover:underline"
          >
            {name}
          </Link>
        )}
      </p>
      <span className="flex shrink-0 items-center">
        {!preview && <ResendButton bet={bet} tableId={table.id} />}
        <BetDetailsDialog
          bet={bet}
          table={table}
          user={user}
          expire={expire}
          peopleKey={peopleKey}
          disabled={preview}
        />
      </span>
    </div>
  );
}

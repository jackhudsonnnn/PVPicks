import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { EmptyState } from "@/components/empty-state";
import { LoadingList } from "@/components/loading";
import { ScrollContainer } from "@/components/scroll-container";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { backendErrorMessage } from "@/lib/api-client";
import { formatCents } from "@/lib/money";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { cn } from "@/lib/utils";
import { MODES } from "@/features/bets";
import { PersonAvatar, type User, usePerson } from "@/features/people";
import { type BalanceTicket, getMemberBalance } from "@/features/tables/api";

interface Props {
  tableId: string;
  /** The member whose balance is open, or null when closed. */
  memberId: string | null;
  /** The table's bets key: every bet change refreshes this along with the table's balances. */
  betsKey: readonly string[];
  onOpenChange: (open: boolean) => void;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/**
 * A member's balance with a what-if (domain rules, "Balances"): the balance assumes every ticket in play loses;
 * checking a ticket under "Win?" adds what it pays if it wins instead. Select all shows the best case, none the
 * balance itself. Nothing here is saved or shared.
 */
export function BalanceDialog({ memberId, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={memberId !== null} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85dvh] max-w-md flex-col gap-4">
        {memberId && <BalanceBody key={memberId} memberId={memberId} {...props} />}
      </DialogContent>
    </Dialog>
  );
}

function BalanceBody({
  tableId,
  memberId,
  betsKey,
  user,
  expire,
  peopleKey,
}: Omit<Props, "memberId" | "onOpenChange"> & { memberId: string }) {
  const person = usePerson(memberId, user, expire, peopleKey);
  const own = memberId === user.id;
  const name = own ? "Your" : person ? `${person.name}'s` : "Their";
  const balance = useQuery({
    queryKey: [...betsKey, "balances", memberId],
    queryFn: ({ signal }) => getMemberBalance(tableId, memberId, signal),
    retry: false,
  });
  useExpireOnUnauthorized([balance.error], expire);
  const [wins, setWins] = useState<ReadonlySet<string>>(new Set());
  const tickets = balance.data?.tickets ?? [];
  // Tickets that resolve while this is open drop out of the what-if on the next refresh.
  const checked = tickets.filter((ticket) => wins.has(ticket.betId));
  const allChecked = tickets.length > 0 && checked.length === tickets.length;
  const whatIf =
    balance.data &&
    checked.reduce(
      (sum, ticket) => sum + ticket.winCents - ticket.loseCents,
      balance.data.balanceCents,
    );
  const toggle = (betId: string) =>
    setWins((current) => {
      const next = new Set(current);
      if (!next.delete(betId)) next.add(betId);
      return next;
    });

  return (
    <>
      <DialogHeader>
        <div className="flex items-center gap-3">
          <PersonAvatar id={memberId} size="md" user={user} expire={expire} queryKey={peopleKey} />
          <div className="min-w-0 text-left">
            <DialogTitle className="break-words">{name} balance</DialogTitle>
            <DialogDescription>
              Assumes every pending ticket loses. Check the ones that win to see what{" "}
              {own ? "you'd" : "they'd"} be at.
            </DialogDescription>
          </div>
        </div>
      </DialogHeader>

      {balance.isError ? (
        <p role="alert" className="text-sm">
          {backendErrorMessage(balance.error)}{" "}
          <button className="underline" onClick={() => void balance.refetch()}>
            Try again
          </button>
        </p>
      ) : !balance.data ? (
        <LoadingList label="Loading balance" rows={3} rowClassName="h-10" />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2">
            <Total label="Balance" cents={balance.data.balanceCents} />
            <Total
              label={allChecked ? "If all win" : "What-if"}
              cents={whatIf ?? balance.data.balanceCents}
              live
            />
          </div>
          {tickets.length === 0 ? (
            <EmptyState className="py-6">
              Nothing in play. {own ? "Your" : "Their"} balance only changes when a bet locks or
              resolves.
            </EmptyState>
          ) : (
            <>
              <div className="flex items-center justify-between gap-2 border-b px-1 pb-2">
                <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                  In play ({tickets.length})
                </span>
                <span className="flex items-center gap-1.5">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={allChecked}
                    onClick={() => setWins(new Set(tickets.map((ticket) => ticket.betId)))}
                  >
                    All win
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={checked.length === 0}
                    onClick={() => setWins(new Set())}
                  >
                    All lose
                  </Button>
                </span>
              </div>
              <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_2.5rem] gap-2 px-1 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                <span>Ticket</span>
                <span className="text-right">Points</span>
                <span className="text-center">Win?</span>
              </div>
              <ScrollContainer isVisible={false} className="-mt-2 min-h-0 divide-y divide-border">
                {tickets.map((ticket) => (
                  <TicketChoice
                    key={ticket.betId}
                    ticket={ticket}
                    checked={wins.has(ticket.betId)}
                    onToggle={() => toggle(ticket.betId)}
                  />
                ))}
              </ScrollContainer>
            </>
          )}
        </>
      )}
    </>
  );
}

function Total({ label, cents, live }: { label: string; cents: bigint; live?: boolean }) {
  return (
    <div className="rounded-lg border bg-surface px-3 py-2">
      <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
        {label}
      </span>
      <span
        aria-live={live ? "polite" : undefined}
        className={cn(
          "scoreboard text-2xl",
          cents === 0n ? "text-muted-foreground" : cents > 0n ? "text-win" : "text-loss",
        )}
      >
        {formatCents(cents, true)}
      </span>
    </div>
  );
}

function TicketChoice({
  ticket,
  checked,
  onToggle,
}: {
  ticket: BalanceTicket;
  checked: boolean;
  onToggle: () => void;
}) {
  const mode = MODES.find((m) => m.id === ticket.mode)?.name ?? ticket.mode;
  const jackblack = ticket.choice === undefined;
  const cents = checked ? ticket.winCents : ticket.loseCents;
  return (
    <label className="grid cursor-pointer grid-cols-[minmax(0,1fr)_4.5rem_2.5rem] items-center gap-2 px-1 py-2.5 text-sm">
      <span className="min-w-0">
        <span className="block break-words font-semibold leading-tight">{ticket.question}</span>
        <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
          {jackblack ? (checked ? "Best case" : "Worst case") : `${mode} · picked ${ticket.choice}`}
        </span>
      </span>
      <span
        className={cn(
          "scoreboard text-right text-lg",
          cents === 0n ? "text-muted-foreground" : cents > 0n ? "text-win" : "text-loss",
        )}
      >
        {formatCents(cents, true)}
      </span>
      <span className="flex justify-center">
        <input
          type="checkbox"
          className="size-4 accent-primary"
          checked={checked}
          onChange={onToggle}
          aria-label={`${ticket.question}: wins`}
        />
      </span>
    </label>
  );
}

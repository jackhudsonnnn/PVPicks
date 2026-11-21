import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonAvatar, PersonName } from "@/features/people";
import { formatClock, ticketStatus } from "@/features/bets/ticket-model";
import { rulesFromConfig } from "@/features/bets/blackjack";
import { RESULT, useBlackjackHand } from "@/features/bets/blackjack-hand";

/** Blackjack's sections in the Details dialog, in place of the pick-mode facts and the picks list. */

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="break-words text-right font-medium">{children}</span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
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
  bet: Bet;
  table: TableRef;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/** The hand at a glance and the dealer's house rules. */
export function BlackjackFacts({
  bet,
  table,
  user,
  expire,
  peopleKey,
  onNavigate,
}: Props & { onNavigate?: (() => void) | undefined }) {
  const rules = rulesFromConfig(bet.config);
  const status = ticketStatus(bet);
  const hand = useBlackjackHand(bet, user, status === "active" || status === "pending").data;
  return (
    <>
      <Section title="Hand">
        <Row label="Table">
          {onNavigate ? (
            <Link
              to="/tables/$tableId"
              params={{ tableId: table.id }}
              onClick={onNavigate}
              className="text-primary underline underline-offset-2"
            >
              {table.emoji} {table.name}
            </Link>
          ) : (
            <>
              {table.emoji} {table.name}
            </>
          )}
        </Row>
        <Row label="Dealer">
          <PersonName id={bet.proposerId} user={user} expire={expire} queryKey={peopleKey} />
        </Row>
        <Row label="Bets">
          {bet.forFun
            ? "4fun, no points"
            : `${formatCents(rules.minBetCents)}–${formatCents(rules.maxBetCents)} pts`}
        </Row>
        <Row label="Seats">
          {hand ? `${hand.seats.length} of ${rules.seats} taken` : `${rules.seats}`}
        </Row>
        <Row label="Join window">{formatClock(rules.joinSec)}</Row>
      </Section>

      <Section title="House rules">
        <Row label="Decks">{rules.decks}</Row>
        <Row label="Blackjack pays">{rules.blackjackPays}</Row>
        <Row label="Dealer on soft 17">{rules.dealerHitsSoft17 ? "Hits" : "Stands"}</Row>
        <Row label="Double down">
          {rules.doubleOn === "any" ? "Any two cards" : "9, 10 or 11"}, after splits too
        </Row>
        <Row label="Splits">
          {rules.maxSplits === 0 ? "None" : `Up to ${rules.maxSplits}; aces get one card each`}
        </Row>
        <Row label="Insurance">{rules.insurance ? "Offered on an ace (pays 2:1)" : "Off"}</Row>
        <Row label="Turns">{rules.turnOrder === "seats" ? "Seat by seat" : "Everyone at once"}</Row>
        <Row label="Decision timer">{rules.decisionSec}s, then you stand</Row>
      </Section>
    </>
  );
}

/** Who sat down, what they bet, how each hand ended, and the dealer's side of it. */
export function BlackjackPlayers({ bet, user, expire, peopleKey }: Props) {
  const status = ticketStatus(bet);
  const hand = useBlackjackHand(bet, user, status === "active" || status === "pending").data;
  const seats = hand?.seats ?? [];
  const dealerDelta = hand?.dealerDeltaCents ?? bet.deltasCents?.[bet.proposerId];
  return (
    <Section title={`Players (${seats.length})`}>
      {seats.length === 0 ? (
        <p className="py-1.5 text-sm text-muted-foreground">
          {status === "active" ? "Nobody has sat down yet." : "Nobody sat down."}
        </p>
      ) : (
        seats.map((seat) => {
          const results = (seat.hands ?? [])
            .map((h) => (h.result ? RESULT[h.result] : h.total))
            .join(" · ");
          return (
            <div key={seat.userId} className="flex items-center gap-2 py-1.5 text-sm">
              <PersonAvatar
                id={seat.userId}
                user={user}
                expire={expire}
                queryKey={peopleKey}
                size="xs"
              />
              <span className="min-w-0 flex-1 break-words">
                <PersonName id={seat.userId} user={user} expire={expire} queryKey={peopleKey} />
                <span className="block text-xs text-muted-foreground">
                  Bet {formatCents(seat.betCents)}
                  {seat.insuranceCents ? ` · insured ${formatCents(seat.insuranceCents)}` : ""}
                  {results && ` · ${results}`}
                </span>
              </span>
              {seat.deltaCents !== undefined && <Delta cents={seat.deltaCents} />}
            </div>
          );
        })
      )}
      {dealerDelta !== undefined && (
        <div className="flex items-center gap-2 py-1.5 text-sm">
          <PersonAvatar
            id={bet.proposerId}
            user={user}
            expire={expire}
            queryKey={peopleKey}
            size="xs"
          />
          <span className="min-w-0 flex-1 break-words">
            <PersonName id={bet.proposerId} user={user} expire={expire} queryKey={peopleKey} />
            <span className="block text-xs text-muted-foreground">
              Dealer
              {hand?.dealerTotal !== undefined &&
                ` · ${hand.dealerTotal > 21 ? "bust" : hand.dealerTotal}`}
            </span>
          </span>
          <Delta cents={dealerDelta} />
        </div>
      )}
    </Section>
  );
}

function Delta({ cents }: { cents: number }) {
  return (
    <span
      className={cn(
        "w-16 text-right font-mono text-xs font-bold",
        cents > 0 ? "text-win" : cents < 0 ? "text-loss" : "text-muted-foreground",
      )}
    >
      {cents === 0 ? "Push" : formatCents(cents, true)}
    </span>
  );
}

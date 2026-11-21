import { Spade } from "lucide-react";
import { StatusBadge } from "./status-badge";
import { BetDetailsDialog } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import { canBetAt, type Table, type TableSummary } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { formatClock, secondsLeft, ticketStatus } from "@/features/bets/ticket-model";
import {
  currentHand,
  myTurn,
  rulesFromConfig,
  type BlackjackHand,
  type BlackjackSeat,
} from "@/features/bets/blackjack";
import { Card, DealerButton, PlayControls, SeatControls } from "./blackjack-parts";
import { RESULT, signed, useBlackjackHand } from "@/features/bets/blackjack-hand";

function Hands({ seat, active }: { seat: BlackjackSeat; active: number }) {
  return (
    <div className="flex flex-wrap gap-3">
      {(seat.hands ?? []).map((hand, i) => (
        <div
          key={i}
          className={cn(
            "space-y-1 rounded-lg p-1.5",
            i === active && "bg-ticket-foreground/8 ring-1 ring-ticket-foreground/30",
          )}
        >
          <div className="flex gap-1">
            {hand.cards.map((c, j) => (
              <Card key={j} code={c} small />
            ))}
          </div>
          <p className="font-mono text-[10px] text-ticket-muted">
            {hand.soft && hand.total < 21 ? `soft ${hand.total}` : hand.total}
            {hand.doubled && " · doubled"}
            {hand.result && (
              <span
                className={cn(
                  "ml-1 font-bold",
                  (hand.deltaCents ?? 0) > 0 && "text-status-resolved",
                  (hand.deltaCents ?? 0) < 0 && "text-loss",
                )}
              >
                · {RESULT[hand.result]}
              </span>
            )}
          </p>
        </div>
      ))}
    </div>
  );
}

function SeatRow({
  seat,
  hand,
  user,
  expire,
  peopleKey,
  now,
}: {
  seat: BlackjackSeat;
  hand: BlackjackHand;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  now: number;
}) {
  const person = usePerson(seat.userId, user, expire, peopleKey);
  const deciding = myTurn(hand, seat);
  const left = seat.deadline
    ? Math.max(0, Math.ceil((Date.parse(seat.deadline) - now) / 1000))
    : null;
  return (
    <li
      className={cn(
        "space-y-2 rounded-lg border border-ticket-foreground/15 p-2.5",
        deciding && "border-ticket-foreground/60",
      )}
    >
      <div className="flex items-center gap-2 text-xs">
        <PersonAvatar id={seat.userId} size="xs" user={user} expire={expire} queryKey={peopleKey} />
        <span className="min-w-0 flex-1 truncate font-semibold">
          {seat.userId === user.id ? "You" : (person?.name ?? "…")}
        </span>
        <span className="font-mono text-[11px] text-ticket-muted">
          {formatCents(seat.betCents)}
          {seat.insuranceCents ? ` · insured ${formatCents(seat.insuranceCents)}` : ""}
        </span>
        {deciding && left !== null && (
          <span className={cn("scoreboard text-base", left <= 5 ? "text-loss" : "")}>{left}s</span>
        )}
        {seat.deltaCents !== undefined && (
          <span
            className={cn(
              "font-mono text-xs font-bold",
              seat.deltaCents > 0 && "text-status-resolved",
              seat.deltaCents < 0 && "text-loss",
            )}
          >
            {signed(seat.deltaCents)}
          </span>
        )}
      </div>
      {seat.hands && <Hands seat={seat} active={deciding ? currentHand(seat) : -1} />}
    </li>
  );
}

/**
 * A Blackjack bet as a ticket: the dealer's cards, every seat's hands, and your controls (sit down while the join
 * window is open, then insurance and hit / stand / double / split on your turn). The hand refreshes with the
 * table's bet feed, and every few seconds while it's being played.
 */
export function BlackjackTicket({
  bet,
  table,
  user,
  expire,
  peopleKey,
  className,
  actions,
}: {
  bet: Bet;
  table: Table | TableSummary;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  className?: string;
  actions?: BetActions;
}) {
  const preview = !actions;
  const status = ticketStatus(bet);
  const rules = rulesFromConfig(bet.config);
  const live = !preview && (status === "active" || status === "pending");
  const hand = useBlackjackHand(bet, user, live, !preview);
  const now = useNow(live);
  const left = secondsLeft(bet, now);
  const dealer = usePerson(bet.proposerId, user, expire, peopleKey);
  const isDealer = bet.proposerId === user.id;
  const blocked = !actions || actions.busy || !!actions.pending || !!actions.storageError;
  const data = hand.data;
  const mine = data?.seats.find((s) => s.userId === user.id);
  const start = (intent: Parameters<BetActions["start"]>[0]) => {
    actions?.start(intent);
  };

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <header className="flex min-h-19 items-center gap-3 py-3 pl-4 pr-10">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
          <Spade className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <p className="font-display text-lg font-extrabold uppercase leading-none">Blackjack</p>
            <StatusBadge status={status} />
            {status === "active" && (
              <span
                className={cn("scoreboard text-lg leading-none", left <= 15 && "text-loss")}
                aria-label="Time left to sit down"
              >
                {left > 0 ? formatClock(left) : "Dealing…"}
              </span>
            )}
          </div>
          <p className="mt-1 line-clamp-2 font-mono text-[11px] leading-tight text-ticket-muted">
            {rules.decks} deck{rules.decks === 1 ? "" : "s"} · pays {rules.blackjackPays} ·{" "}
            {rules.dealerHitsSoft17 ? "hits" : "stands on"} soft 17 · {rules.seats} seats
          </p>
        </div>
      </header>

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <div className="flex items-start justify-between gap-2">
          <p className="flex min-w-0 items-center gap-1.5 text-xs text-ticket-muted">
            <span className="shrink-0">Dealer</span>
            <PersonAvatar
              id={bet.proposerId}
              size="xs"
              user={user}
              expire={expire}
              queryKey={peopleKey}
            />
            <span className="truncate font-semibold text-ticket-foreground">
              {isDealer ? "You" : (dealer?.name ?? "…")}
            </span>
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
          <span className="-mt-2 inline-block rounded-full bg-ticket-foreground px-2 py-0.5 font-mono text-[10px] font-bold uppercase text-ticket">
            4fun
          </span>
        )}

        {preview ? (
          <p className="text-sm text-ticket-muted">
            Bets {formatCents(rules.minBetCents)}–{formatCents(rules.maxBetCents)} points. Players
            sit down while the join window is open; then the cards come out.
          </p>
        ) : hand.isPending ? (
          <p className="text-sm text-ticket-muted">Shuffling…</p>
        ) : !data ? (
          <p className="text-sm text-ticket-muted">The hand can't be shown right now.</p>
        ) : (
          <>
            {data.dealer.length > 0 && (
              <div className="space-y-1">
                <div className="flex gap-1">
                  {data.dealer.map((c, i) => (
                    <Card key={i} code={c} />
                  ))}
                </div>
                <p className="font-mono text-[10px] text-ticket-muted">
                  Dealer{data.dealerTotal !== undefined && ` · ${data.dealerTotal}`}
                  {data.dealerTotal !== undefined && data.dealerTotal > 21 && " · bust"}
                  {data.dealerDeltaCents !== undefined &&
                    data.dealerDeltaCents !== 0 &&
                    ` · ${signed(data.dealerDeltaCents)}`}
                </p>
              </div>
            )}

            {data.seats.length === 0 ? (
              <p className="text-sm text-ticket-muted">
                {status === "washed" ? bet.washReason : "Nobody has sat down yet."}
              </p>
            ) : (
              <ul className="space-y-2" aria-label="Seats">
                {data.seats.map((seat) => (
                  <SeatRow
                    key={seat.userId}
                    seat={seat}
                    hand={data}
                    user={user}
                    expire={expire}
                    peopleKey={peopleKey}
                    now={now}
                  />
                ))}
              </ul>
            )}

            {status === "active" && !isDealer && canBetAt(table, user.id) && left > 0 && (
              <SeatControls
                key={mine?.betCents ?? "new"}
                rules={rules}
                mine={mine}
                full={data.seats.length >= rules.seats}
                disabled={blocked}
                onSit={(betCents) =>
                  start({ kind: "blackjack-sit", tableId: table.id, betId: bet.id, betCents })
                }
                onLeave={() => start({ kind: "blackjack-leave", tableId: table.id, betId: bet.id })}
              />
            )}
            {status === "active" && isDealer && (
              <p className="text-xs text-ticket-muted">
                You're dealing. Cards come out when the timer ends.
              </p>
            )}
            {mine && myTurn(data, mine) && (
              <PlayControls
                hand={data}
                seat={mine}
                rules={rules}
                disabled={blocked}
                onMove={(action) =>
                  start({ kind: "blackjack-act", tableId: table.id, betId: bet.id, action })
                }
              />
            )}
            {isDealer && data.dealerMove && (
              <div className="space-y-1">
                <p className="text-xs text-ticket-muted">Your turn to deal.</p>
                <DealerButton
                  move={data.dealerMove}
                  disabled={blocked}
                  onTap={() =>
                    start({
                      kind: "blackjack-act",
                      tableId: table.id,
                      betId: bet.id,
                      action: "dealer",
                    })
                  }
                />
              </div>
            )}
            {mine && data.phase === "play" && !myTurn(data, mine) && currentHand(mine) >= 0 && (
              <p className="text-xs text-ticket-muted">Waiting for your turn…</p>
            )}
          </>
        )}
      </div>
    </article>
  );
}

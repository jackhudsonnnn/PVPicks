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
  secondsUntil,
  type BlackjackHand,
  type BlackjackSeat,
} from "@/features/bets/blackjack";
import { Card, DealerButton, PlayControls, SeatControls } from "./blackjack-parts";
import { RESULT, signed, useBlackjackHand } from "@/features/bets/blackjack-hand";

/** Cards in the ticket come out of the shoe at the felt's top left. */
const SHOE_FROM_DEALER = { x: -64, y: 0 };
const SHOE_FROM_SEAT = { x: -48, y: -110 };
/** Seconds between cards in the opening deal. */
const DEAL_GAP = 0.14;

const BACK = "border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_3px,#7f1d1d_3px_6px)]";

/** One player's spot on the ticket's felt: name, bet, their hands, and how it's going. */
function Spot({
  seat,
  order,
  players,
  hand,
  user,
  expire,
  peopleKey,
  now,
}: {
  seat: BlackjackSeat;
  order: number;
  players: number;
  hand: BlackjackHand;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  now: number;
}) {
  const person = usePerson(seat.userId, user, expire, peopleKey);
  const deciding = myTurn(hand, seat);
  const active = deciding ? currentHand(seat) : -1;
  const left = deciding ? secondsUntil(seat.deadline, now) : null;
  return (
    <li
      className={cn(
        "min-w-0 space-y-1.5 rounded-lg border border-white/10 bg-black/20 p-2",
        deciding && "border-primary/70 bg-primary/10",
      )}
    >
      <div className="flex items-center gap-1.5 text-[11px]">
        <PersonAvatar id={seat.userId} size="xs" user={user} expire={expire} queryKey={peopleKey} />
        <span className="min-w-0 flex-1 truncate font-semibold text-white">
          {seat.userId === user.id ? "You" : (person?.name ?? "…")}
        </span>
        {left !== null ? (
          <span className={cn("scoreboard text-sm", left <= 5 ? "text-loss" : "text-white")}>
            {left}s
          </span>
        ) : (
          <span className="font-mono text-[10px] text-white/60">{formatCents(seat.betCents)}</span>
        )}
      </div>
      {seat.hands ? (
        <div className="flex flex-wrap gap-1.5">
          {seat.hands.map((h, i) => (
            <div
              key={i}
              className={cn("space-y-1 rounded-md p-1", i === active && "ring-2 ring-primary")}
            >
              <div className="flex -space-x-2">
                {h.cards.map((code, c) => (
                  <Card
                    key={`${c}-${code}`}
                    code={code}
                    small
                    from={SHOE_FROM_SEAT}
                    delay={
                      h.cards.length <= 2 && seat.hands!.length === 1
                        ? (c * (players + 1) + order) * DEAL_GAP
                        : 0
                    }
                  />
                ))}
              </div>
              <p
                className={cn(
                  "font-mono text-[10px] font-bold text-white/80",
                  (h.deltaCents ?? 0) > 0 && "text-win",
                  (h.deltaCents ?? 0) < 0 && "text-loss",
                )}
              >
                {h.result ? RESULT[h.result] : h.soft && h.total < 21 ? `soft ${h.total}` : h.total}
                {h.doubled && " · 2×"}
              </p>
            </div>
          ))}
        </div>
      ) : (
        <p className="font-mono text-[10px] text-white/60">Bet {formatCents(seat.betCents)}</p>
      )}
      {seat.deltaCents !== undefined && (
        <p
          className={cn(
            "font-mono text-[11px] font-bold",
            seat.deltaCents > 0 ? "text-win" : seat.deltaCents < 0 ? "text-loss" : "text-white/70",
          )}
        >
          {seat.deltaCents === 0 ? "Push" : signed(seat.deltaCents)}
          {seat.insuranceCents ? ` · insured ${formatCents(seat.insuranceCents)}` : ""}
        </p>
      )}
    </li>
  );
}

/** What's happening, in one line under the felt. */
function statusLine(
  data: BlackjackHand,
  bet: Bet,
  user: User,
  mine: BlackjackSeat | undefined,
  now: number,
  joinLeft: number,
) {
  const dealing = bet.proposerId === user.id;
  if (data.phase === "waiting")
    return dealing
      ? `You're dealing. Cards come out in ${formatClock(Math.max(0, joinLeft))}.`
      : mine
        ? `You're in for ${formatCents(mine.betCents)}. Cards come out in ${formatClock(Math.max(0, joinLeft))}.`
        : "Sit down before the cards come out.";
  if (data.phase === "insurance") return "The dealer shows an ace: insurance?";
  if (data.phase === "dealer") {
    const left = secondsUntil(data.dealerDeadline, now);
    return dealing ? `Your turn to deal${left !== null ? ` · ${left}s` : ""}.` : "Dealer's turn…";
  }
  if (data.phase === "done") {
    if (mine?.deltaCents !== undefined)
      return mine.deltaCents === 0
        ? "Push."
        : `You ${mine.deltaCents > 0 ? "won" : "lost"} ${formatCents(Math.abs(mine.deltaCents))}.`;
    if (dealing && data.dealerDeltaCents !== undefined)
      return `As dealer: ${data.dealerDeltaCents === 0 ? "even" : signed(data.dealerDeltaCents)}.`;
    return "Hand over.";
  }
  if (mine && myTurn(data, mine)) return "Your move.";
  if (mine && currentHand(mine) >= 0) return "Waiting for your turn…";
  return "Players deciding…";
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
  const revealed = !!data && data.dealer[1] !== undefined && data.dealer[1] !== "??";
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
            {/* The table: the dealer and the shoe at the top, everyone's spot below. */}
            <div className="space-y-3 rounded-xl bg-[radial-gradient(ellipse_at_top,#1f6b3a,#0f3d22)] p-3 shadow-inner">
              <div className="flex items-center gap-3">
                <span className="relative block h-14 w-10 shrink-0" aria-hidden>
                  <span
                    className={cn(
                      "absolute inset-0 translate-x-1 translate-y-1 rounded-md border shadow",
                      BACK,
                    )}
                  />
                  <span className={cn("absolute inset-0 rounded-md border shadow-md", BACK)} />
                </span>
                <div className="min-w-0 flex-1">
                  {data.dealer.length > 0 ? (
                    <div className="flex -space-x-3">
                      {data.dealer.map((code, c) => (
                        <Card
                          key={`${c}-${code}`}
                          code={code}
                          // Face down, cards slide from the shoe in deal order; turned over, the hole card flips.
                          from={revealed && c === 1 ? undefined : SHOE_FROM_DEALER}
                          delay={
                            !revealed
                              ? (c * (data.seats.length + 1) + data.seats.length) * DEAL_GAP
                              : 0
                          }
                        />
                      ))}
                    </div>
                  ) : (
                    <p className="text-xs text-white/60">Waiting to deal</p>
                  )}
                  <p className="mt-1 font-mono text-[10px] font-bold uppercase tracking-wider text-white/80">
                    Dealer · {isDealer ? "you" : (dealer?.name ?? "…")}
                    {data.dealerTotal !== undefined &&
                      ` · ${data.dealerTotal > 21 ? "bust" : data.dealerTotal}`}
                  </p>
                </div>
              </div>
              {data.seats.length === 0 ? (
                <p className="py-2 text-center text-xs text-white/60">
                  {status === "washed" ? bet.washReason : "Nobody has sat down yet."}
                </p>
              ) : (
                <ul className="grid grid-cols-2 gap-2" aria-label="Seats">
                  {data.seats.map((seat, order) => (
                    <Spot
                      key={seat.userId}
                      seat={seat}
                      order={order}
                      players={data.seats.length}
                      hand={data}
                      user={user}
                      expire={expire}
                      peopleKey={peopleKey}
                      now={now}
                    />
                  ))}
                </ul>
              )}
            </div>

            <p className="text-sm font-semibold" aria-live="polite">
              {statusLine(data, bet, user, mine, now, left)}
            </p>

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
            )}
          </>
        )}
      </div>
    </article>
  );
}

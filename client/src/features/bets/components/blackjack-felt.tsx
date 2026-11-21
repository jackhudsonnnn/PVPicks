import { useEffect, useRef, useState } from "react";
import { Card, DealerButton, PlayControls } from "./blackjack-parts";
import type { BetActions } from "./bet-controls";
import { cn } from "@/lib/utils";
import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import type { Table } from "@/features/tables";
import { type User, usePerson } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { signed, useBlackjackHand } from "@/features/bets/blackjack-hand";
import {
  currentHand,
  myTurn,
  rulesFromConfig,
  secondsUntil,
  type BlackjackHand,
  type BlackjackMove,
  type BlackjackSeat,
} from "@/features/bets/blackjack";

/** Where things sit on the felt, in percent of its width and height. */
type Spot = { left: number; top: number };
type Size = { width: number; height: number };
/** The dealer's cards when their seat isn't drawn (they're on the rail): the top of the felt. */
const DEALER_FALLBACK: Spot = { left: 50, top: 23 };
const CENTER: Spot = { left: 50, top: 50 };
/**
 * How far cards sit from their seat toward the middle, clear of the name and of the ticket stack: across, then down
 * from the top seats (more room there) or up from the bottom ones (the controls are above).
 */
const TOWARD_MIDDLE = { x: 0.35, top: 0.45, bottom: 0.25 };
/** Seconds between cards in the opening deal. */
const DEAL_GAP = 0.14;
/** The shoe sits this far left of the dealer's cards (px), so every card leaves from in front of the dealer. */
const SHOE_OFFSET = 46;

function useSize<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [size, setSize] = useState<Size>({ width: 0, height: 0 });
  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setSize({ width: entry.contentRect.width, height: entry.contentRect.height });
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, size] as const;
}

/** A seat's cards: part of the way from the seat toward the middle (`extra` nudges a row clear of the avatar). */
function inFront(seat: Spot, extra = 0): Spot {
  return {
    left: seat.left + (CENTER.left - seat.left) * TOWARD_MIDDLE.x,
    top:
      seat.top +
      (CENTER.top - seat.top) *
        ((seat.top < CENTER.top ? TOWARD_MIDDLE.top : TOWARD_MIDDLE.bottom) + extra),
  };
}

/** Pixels from where a card lands (`at`) back to the shoe (`shoe`, px), so it can slide out of it. */
function fromShoe(at: Spot, shoe: { x: number; y: number }, size: Size) {
  return {
    x: shoe.x - (at.left / 100) * size.width,
    y: shoe.y - (at.top / 100) * size.height,
  };
}

const BACK = "border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_3px,#7f1d1d_3px_6px)]";

function Hands({
  seat,
  at,
  shoe,
  order,
  players,
  size,
  active,
  user,
}: {
  seat: BlackjackSeat;
  at: Spot;
  shoe: { x: number; y: number };
  order: number;
  players: number;
  size: Size;
  active: number;
  user: User;
}) {
  const from = fromShoe(at, shoe, size);
  return (
    <div
      className="pointer-events-none absolute flex -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-0.5"
      style={{ left: `${at.left}%`, top: `${at.top}%` }}
    >
      <div className="flex gap-1.5">
        {(seat.hands ?? []).map((hand, h) => (
          <div
            key={h}
            className={cn(
              "flex -space-x-1.5 rounded-md p-0.5",
              h === active && "ring-2 ring-primary",
            )}
          >
            {hand.cards.map((code, c) => (
              <Card
                key={`${c}-${code}`}
                code={code}
                tiny
                from={from}
                // The opening deal goes round the table twice; later cards come straight away.
                delay={
                  hand.cards.length <= 2 && seat.hands!.length === 1
                    ? (c * (players + 1) + order) * DEAL_GAP
                    : 0
                }
              />
            ))}
          </div>
        ))}
      </div>
      <span
        className={cn(
          "rounded-full bg-felt-rim/90 px-1.5 font-mono text-[9px] font-bold sm:text-[10px]",
          seat.deltaCents !== undefined && seat.deltaCents > 0 && "text-win",
          seat.deltaCents !== undefined && seat.deltaCents < 0 && "text-loss",
        )}
      >
        {seat.deltaCents !== undefined
          ? seat.deltaCents === 0
            ? "Push"
            : signed(seat.deltaCents)
          : (seat.hands ?? []).map((h) => h.total).join(" / ")}
        {seat.userId === user.id && seat.deltaCents === undefined && " · you"}
      </span>
    </div>
  );
}

function TurnName({
  hand,
  user,
  expire,
  peopleKey,
}: {
  hand: BlackjackHand;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}) {
  const seat = hand.seats.find((s) => s.seat === hand.turnSeat);
  const person = usePerson(seat?.userId ?? "", user, expire, peopleKey);
  if (!seat) return null;
  return <>{seat.userId === user.id ? "Your" : `${person?.name ?? "…"}'s`} turn</>;
}

/**
 * Blackjack's cards right on the table's felt, around the usual ticket stack. Everything is dealt from in front of
 * the dealer's seat: their cards sit there with the shoe beside them, and every card slides out of that shoe to the
 * player's spot in front of their own seat; the hole card flips over when the dealer turns it. Seats come from the
 * felt (`seatSpot`); someone on the rail is still in the hand, just without cards drawn by their seat.
 * `BlackjackStatus` and `BlackjackControls` go with the stack.
 */
export function BlackjackCards({
  bet,
  user,
  seatSpot,
}: {
  bet: Bet;
  user: User;
  seatSpot: (userId: string) => Spot | undefined;
}) {
  const [ref, size] = useSize<HTMLDivElement>();
  const status = ticketStatus(bet);
  const live = status === "active" || status === "pending";
  const data = useBlackjackHand(bet, user, live).data;
  const seats = (data?.seats ?? []).filter((s) => s.hands);
  const dealerSeat = seatSpot(bet.proposerId);
  const dealerAt = dealerSeat ? inFront(dealerSeat, 0.04) : DEALER_FALLBACK;
  // The shoe, in pixels: just left of the dealer's cards.
  const dealerCards = data?.dealer.length ?? 0;
  const shoe = {
    x: (dealerAt.left / 100) * size.width - SHOE_OFFSET - Math.max(0, dealerCards - 2) * 9,
    y: (dealerAt.top / 100) * size.height,
  };
  const revealed = !!data && data.dealer[1] !== undefined && data.dealer[1] !== "??";

  return (
    <div ref={ref} className="pointer-events-none absolute inset-0">
      {/* The dealer: their cards in front of their seat, with the shoe beside them. */}
      <div
        className="absolute flex -translate-x-1/2 -translate-y-1/2 items-center gap-2"
        style={{ left: `${dealerAt.left}%`, top: `${dealerAt.top}%` }}
      >
        <div className="flex items-center gap-2">
          <span className="relative block h-8 w-6" aria-hidden>
            <span
              className={cn(
                "absolute inset-0 translate-x-0.5 translate-y-0.5 rounded-md border shadow",
                BACK,
              )}
            />
            <span className={cn("absolute inset-0 rounded-md border shadow-md", BACK)} />
          </span>
          {data && data.dealer.length > 0 && (
            <div className="flex -space-x-1.5">
              {data.dealer.map((code, c) => (
                <Card
                  key={`${c}-${code}`}
                  code={code}
                  tiny
                  // Face down, cards slide out in deal order; turned over, the hole card flips in place and the
                  // dealer's draws slide out one at a time.
                  from={revealed && c === 1 ? undefined : fromShoe(dealerAt, shoe, size)}
                  delay={!revealed ? (c * (seats.length + 1) + seats.length) * DEAL_GAP : 0}
                />
              ))}
            </div>
          )}
        </div>
        {data && data.dealer.length > 0 && (
          <span className="rounded-full bg-felt-rim/90 px-1.5 font-mono text-[9px] font-bold sm:text-[10px]">
            Dealer
            {data.dealerTotal !== undefined &&
              ` · ${data.dealerTotal > 21 ? "bust" : data.dealerTotal}`}
          </span>
        )}
      </div>

      {/* Everyone's cards in front of their seat. */}
      {data &&
        seats.map((seat, order) => {
          const spot = seatSpot(seat.userId);
          if (!spot) return null;
          return (
            <Hands
              key={seat.userId}
              seat={seat}
              at={inFront(spot)}
              shoe={shoe}
              order={order}
              players={seats.length}
              size={size}
              active={myTurn(data, seat) ? currentHand(seat) : -1}
              user={user}
            />
          );
        })}
    </div>
  );
}

/** One line for the felt's bet pill: how many are in, whose move it is (with your timer), or how it ended. */
export function BlackjackStatus({
  bet,
  user,
  expire,
  peopleKey,
}: {
  bet: Bet;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}) {
  const status = ticketStatus(bet);
  const live = status === "active" || status === "pending";
  const data = useBlackjackHand(bet, user, live).data;
  const now = useNow(live);
  const mine = data?.seats.find((s) => s.userId === user.id);
  const dealing = bet.proposerId === user.id;
  // Like every mode's pill while it's open: how many are in.
  if (status === "active") return <>{data?.seats.length ?? 0} in</>;
  if (status === "washed") return <>Washed</>;
  if (!data) return <>Blackjack</>;
  if (data.phase === "done") {
    if (mine?.deltaCents !== undefined)
      return <>{mine.deltaCents === 0 ? "Push" : `You ${signed(mine.deltaCents)}`}</>;
    if (dealing && data.dealerDeltaCents !== undefined)
      return <>Dealer {signed(data.dealerDeltaCents)}</>;
    return <>Hand over</>;
  }
  if (data.phase === "dealer") {
    const left = secondsUntil(data.dealerDeadline, now);
    return dealing ? <>Your move{left !== null && ` · ${left}s`}</> : <>Dealer's turn</>;
  }
  if (data.phase === "insurance") return <>Insurance?</>;
  if (mine && myTurn(data, mine)) {
    const left = secondsUntil(mine.deadline, now);
    return <>Your move{left !== null && ` · ${left}s`}</>;
  }
  if (data.turnSeat !== undefined)
    return <TurnName hand={data} user={user} expire={expire} peopleKey={peopleKey} />;
  return <>Players deciding</>;
}

/**
 * Your move under the stack once the cards are out: insurance, then hit / stand / double / split, and the dealer's
 * one button (flip, hit, stand) on their turn. Sitting down happens on the ticket.
 */
export function BlackjackControls({
  bet,
  table,
  user,
  actions,
}: {
  bet: Bet;
  table: Table;
  user: User;
  actions: BetActions;
}) {
  const status = ticketStatus(bet);
  const data = useBlackjackHand(bet, user, status === "active" || status === "pending").data;
  const mine = data?.seats.find((s) => s.userId === user.id);
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const dealerTurn = !!data?.dealerMove && bet.proposerId === user.id;
  if (!data || (!dealerTurn && !(mine && myTurn(data, mine)))) return null;
  const move = (action: BlackjackMove) =>
    actions.start({ kind: "blackjack-act", tableId: table.id, betId: bet.id, action });
  return (
    <div className="w-[min(78vw,17rem)] rounded-xl border border-foreground/10 bg-felt-rim/90 p-2 shadow-xl backdrop-blur-sm">
      {dealerTurn ? (
        <DealerButton move={data.dealerMove!} disabled={blocked} onTap={() => move("dealer")} />
      ) : (
        <PlayControls
          hand={data}
          seat={mine!}
          rules={rulesFromConfig(bet.config)}
          disabled={blocked}
          onMove={move}
        />
      )}
    </div>
  );
}

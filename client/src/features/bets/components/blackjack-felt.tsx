import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import { type User, usePerson } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { signed, useBlackjackHand } from "@/features/bets/blackjack-hand";
import { myTurn, secondsUntil, type BlackjackHand } from "@/features/bets/blackjack";

/**
 * Blackjack on the table's felt stays light: the usual ticket stack, a status line in its pill
 * (`BlackjackStatus`), a "Your move" button under it on your turn (`BlackjackPrompt`), and each seat's chip from
 * `useBlackjackSeatChips`. The hand itself is played on the ticket.
 */

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
 * Under the felt's stack when it's your move (as a player or the dealer): one pulsing button with your timer that
 * opens the ticket, where the hand is played.
 */
export function BlackjackPrompt({
  bet,
  user,
  onOpen,
}: {
  bet: Bet;
  user: User;
  onOpen: () => void;
}) {
  const status = ticketStatus(bet);
  const live = status === "active" || status === "pending";
  const data = useBlackjackHand(bet, user, live).data;
  const now = useNow(live);
  if (!data) return null;
  const mine = data.seats.find((s) => s.userId === user.id);
  const dealerTurn = !!data.dealerMove && bet.proposerId === user.id;
  const playerTurn = !!mine && myTurn(data, mine);
  if (!dealerTurn && !playerTurn) return null;
  const left = secondsUntil(dealerTurn ? data.dealerDeadline : mine?.deadline, now);
  return (
    <button
      type="button"
      onClick={onOpen}
      className="animate-pulse rounded-full bg-primary px-4 py-2 font-display text-sm font-extrabold uppercase text-primary-foreground shadow-lg hover:animate-none"
    >
      Your move{left !== null && ` · ${left}s`}
    </button>
  );
}

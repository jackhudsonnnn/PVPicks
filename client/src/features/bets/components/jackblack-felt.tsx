import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import type { User } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { signed, useJackblackGame } from "@/features/bets/jackblack-game";
import { deciding, playersOf, secondsUntil, undecided } from "@/features/bets/jackblack";

/**
 * Jackblack on the table's felt stays light: the usual ticket stack, a status line in its pill
 * (`JackblackStatus`, pulsing "Your move" when you have a choice or the dealer's button; tap the ticket to play),
 * and each player's chip from `useJackblackSeatChips`. The game itself is played on the ticket.
 */

/** "Your move · 12s" in the pill, in the brand color and pulsing, so it's hard to miss. */
function YourMove({ left }: { left: number | null }) {
  return (
    <span className="animate-pulse text-primary">Your move{left !== null && ` · ${left}s`}</span>
  );
}

/** One line for the felt's bet pill: how many are in, whether it's your move, or how it ended. */
export function JackblackStatus({ bet, user }: { bet: Bet; user: User }) {
  const status = ticketStatus(bet);
  const live = status === "active" || status === "pending";
  const data = useJackblackGame(bet, user, live).data;
  const now = useNow(live);
  // Like every mode's pill while it's open: how many are in.
  if (status === "active") return <>{data ? playersOf(data).length : 1} in</>;
  if (status === "washed") return <>Washed</>;
  if (!data) return <>Jackblack</>;
  if (data.phase === "done") {
    const mine = data.totals?.[user.id];
    return mine === undefined ? <>Game over</> : <>You {mine === 0 ? "even" : signed(mine)}</>;
  }
  const left = secondsUntil(data.deadline, now);
  const round = `Round ${data.round}/${data.rounds}`;
  if (data.phase === "dealer")
    return data.dealerId === user.id ? <YourMove left={left} /> : <>{round} · dealer</>;
  if (deciding(data, user.id)) return <YourMove left={left} />;
  return (
    <>
      {round} · {undecided(data)} deciding
    </>
  );
}

import type { Bet } from "@/features/bets/api";
import type { User } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { useJackblackGame } from "@/features/bets/jackblack-game";
import { cn } from "@/lib/utils";
import { MiniCards } from "./holdem-felt";
import { deciding, playersOf } from "@/features/bets/jackblack";

/**
 * Jackblack on the table's felt stays light: the usual ticket stack, a status line in its pill
 * (`JackblackStatus`, pulsing "Your move" when you have a choice or the dealer's button; tap the ticket to play),
 * and each player's chip from `useJackblackSeatChips`. The game itself is played on the ticket.
 */

/**
 * The felt pill's count for a Jackblack game: how many are in, like every mode's pill, in the brand color and pulsing
 * when you have a choice to make or the dealer's button.
 */
export function JackblackStatus({ bet, user }: { bet: Bet; user: User }) {
  const status = ticketStatus(bet);
  const live = status === "active" || status === "pending";
  const data = useJackblackGame(bet, user, live).data;
  const n = data ? playersOf(data).length : 1;
  const yourMove =
    !!data && (deciding(data, user.id) || (data.phase === "dealer" && data.dealerId === user.id));
  return <span className={cn(yourMove && "animate-pulse text-primary")}>{n} in</span>;
}

/** The dealer's hand on the felt's ticket card: the up-card and the hole card (face down until it's turned). */
export function JackblackMiniHand({ bet, user }: { bet: Bet; user: User }) {
  const status = ticketStatus(bet);
  const data = useJackblackGame(bet, user, status === "active" || status === "pending").data;
  const cards =
    data?.phase === "play" || data?.phase === "dealer"
      ? (data.dealer ?? [])
      : data?.phase === "results"
        ? (data.history?.at(-1)?.dealer ?? [])
        : [];
  return <MiniCards cards={cards} slots={2} />;
}

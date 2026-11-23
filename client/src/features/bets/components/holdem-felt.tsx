import type { Bet } from "@/features/bets/api";
import type { User } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { cn } from "@/lib/utils";
import { holdemPlayers } from "@/features/bets/holdem";
import { useHoldemGame } from "@/features/bets/holdem-game";
import { cardFace } from "@/features/bets/cards";

/**
 * Hold'em on the table's felt stays light like Jackblack: the usual ticket stack (its front card shows the board),
 * the pill's "N in" (pulsing on your turn; tap the ticket to play), and each player's chip from `useHoldemSeatChips`.
 */
export function HoldemStatus({ bet, user }: { bet: Bet; user: User }) {
  const status = ticketStatus(bet);
  const data = useHoldemGame(bet, user, status === "active" || status === "pending").data;
  const n = data ? holdemPlayers(data).length : 1;
  const yourMove = data?.phase === "play" && data.toActId === user.id;
  return <span className={cn(yourMove && "animate-pulse text-primary")}>{n} in</span>;
}

/**
 * The board on the felt's ticket card, compact enough for it: five cards face down before the flop, then the flop,
 * turn and river turned over.
 */
export function HoldemMiniBoard({ bet, user }: { bet: Bet; user: User }) {
  const status = ticketStatus(bet);
  const data = useHoldemGame(bet, user, status === "active" || status === "pending").data;
  const board = data?.phase === "play" ? (data.board ?? []) : (data?.history?.at(-1)?.board ?? []);
  return <MiniCards cards={board} slots={5} />;
}

/**
 * Small cards for the felt's ticket card: `cards` face up ("??" face down), the rest of `slots` face down too (cards
 * still to come), so a game that hasn't dealt yet shows the felt with nothing flipped.
 */
export function MiniCards({ cards, slots }: { cards: string[]; slots: number }) {
  const board = cards;
  return (
    <span className="flex gap-0.5" aria-label={`Cards: ${board.join(" ") || "none yet"}`}>
      {Array.from({ length: Math.max(slots, board.length) }, (_, i) => {
        const face = board[i] && board[i] !== "??" ? cardFace(board[i]) : null;
        return !face ? (
          <span
            key={i}
            className="h-6 w-4 rounded-sm border border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_2px,#7f1d1d_2px_4px)] sm:h-7 sm:w-5"
          />
        ) : (
          <span
            key={i}
            className={cn(
              "flex h-6 w-4 flex-col items-center justify-center rounded-sm bg-white text-[8px] font-bold leading-none sm:h-7 sm:w-5 sm:text-[9px]",
              face.red ? "text-red-600" : "text-zinc-900",
            )}
          >
            <span>{face.rank}</span>
            <span>{face.suit}</span>
          </span>
        );
      })}
    </span>
  );
}

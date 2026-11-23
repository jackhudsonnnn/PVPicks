import { formatCents } from "@/lib/money";
import { STATUSES, type Bet } from "@/features/bets";

/** Seats around the felt. With more people than seats, the last seat becomes an "N others" spot. */
const SEAT_CAP = 8;

/**
 * Who sits at the felt. While the focused bet is Active nobody's participation is public yet, so everyone sits;
 * once it locks, only the people in it (pickers and No guess; for Jackblack, its players). You take the bottom seat when you're seated; the
 * rest keep table order.
 */
export function arrangeSeats(
  memberIds: string[],
  me: string,
  focused: Bet | undefined,
  cap = SEAT_CAP,
) {
  const inBet =
    // A finished card game (Jackblack, Hold'em) stays on the felt a little while; keep its players seated.
    (focused?.status === "PENDING" ||
      (!!focused && isCardGame(focused.modeId) && focused.status !== "ACTIVE")) &&
    focused.picks &&
    focused.noGuessUserIds
      ? new Set([...focused.picks.map((pick) => pick.userId), ...focused.noGuessUserIds])
      : null;
  const eligible = inBet ? memberIds.filter((id) => inBet.has(id)) : memberIds;
  const others = eligible.filter((id) => id !== me);
  const all = eligible.includes(me) ? [me, ...others] : others;
  // `fromBet`: everyone seated (and on the rail) is in the focused ticket, not just at the table.
  const fromBet = inBet !== null;
  if (all.length <= cap) return { seated: all, rail: [] as string[], fromBet };
  return { seated: all.slice(0, cap - 1), rail: all.slice(cap - 1), fromBet };
}

/** Seat centers on the table's oval edge, as % of the felt box. Seat 0 sits at the bottom. */
export function seatPosition(index: number, count: number) {
  const angle = ((90 + (index * 360) / count) * Math.PI) / 180;
  return { left: 50 + 50 * Math.cos(angle), top: 50 + 50 * Math.sin(angle) };
}

export type SeatTone = "pick" | "pass" | "win" | "loss" | "washed";

/**
 * The chip under a seat for the focused bet. While Active only your own pick is known, so others show nothing.
 */
export function seatStatus(
  bet: Bet | undefined,
  userId: string,
  me: string,
): { label: string; tone: SeatTone } | null {
  if (!bet) return null;
  if (isCardGame(bet.modeId)) return cardGameSeat(bet, userId, me);
  if (bet.status === "ACTIVE") {
    if (userId !== me) return null;
    if (bet.myParticipation === "picked") return { label: bet.myPick!, tone: "pick" };
    return bet.myParticipation === "no_guess" ? { label: "No guess", tone: "pass" } : null;
  }
  const pick = bet.picks?.find((p) => p.userId === userId);
  if (!pick)
    return bet.noGuessUserIds?.includes(userId) ? { label: "No guess", tone: "pass" } : null;
  if (bet.status === "PENDING") return { label: pick.choice, tone: "pick" };
  if (bet.status === "WASHED") return { label: STATUSES.WASHED.label, tone: "washed" };
  if (bet.forFun)
    return pick.choice === bet.winningChoice
      ? { label: "Won", tone: "win" }
      : { label: "Lost", tone: "loss" };
  const delta = bet.deltasCents?.[userId] ?? 0;
  return delta >= 0
    ? { label: `Won ${formatCents(delta, true)}`, tone: "win" }
    : { label: `Lost ${formatCents(-delta)}`, tone: "loss" };
}

/** The card games (Jackblack, Hold'em) are played on their tickets, never picked. */
export const isCardGame = (modeId: Bet["modeId"]) => modeId === "jackblack" || modeId === "holdem";

/**
 * The card games have no picks: their players are the bet's participants, which the server lists as No guess. (The
 * felt shows the live game's chips from the game itself; this is the fallback.)
 */
function cardGameSeat(
  bet: Bet,
  userId: string,
  me: string,
): { label: string; tone: SeatTone } | null {
  const playing =
    bet.status === "ACTIVE"
      ? userId === me && bet.myParticipation !== "not_joined"
      : !!bet.noGuessUserIds?.includes(userId);
  if (!playing) return null;
  if (bet.status === "ACTIVE") return { label: "In", tone: "pick" };
  if (bet.status === "PENDING") return { label: "Playing", tone: "pick" };
  if (bet.status === "WASHED") return { label: STATUSES.WASHED.label, tone: "washed" };
  const delta = bet.deltasCents?.[userId] ?? 0;
  if (bet.forFun || delta === 0) return { label: "Even", tone: "pass" };
  return delta > 0
    ? { label: `Up ${formatCents(delta, true)}`, tone: "win" }
    : { label: `Down ${formatCents(-delta)}`, tone: "loss" };
}

/** How long a finished card game stays on the felt, so everyone sees how it ended. */
export const FINISHED_HAND_MS = 20_000;

/** Open bets for the deck, newest first, plus a card game that just finished. */
export function openBets(bets: Bet[], now = Date.now()) {
  return bets
    .filter(
      (bet) =>
        bet.status === "ACTIVE" ||
        bet.status === "PENDING" ||
        (isCardGame(bet.modeId) &&
          !!bet.closedAt &&
          now - Date.parse(bet.closedAt) < FINISHED_HAND_MS),
    )
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

import { formatCents } from "@/lib/money";
import type { BackendBet } from "./backend-api";

/** Seats around the felt. With more people than seats, the last seat becomes an "N others" spot. */
export const SEAT_CAP = 8;

/**
 * Who sits at the felt. While the focused bet is Active nobody's participation is public yet, so everyone sits;
 * once it locks, only the people in it (pickers and No guess). You take the bottom seat when you're seated; the
 * rest keep table order.
 */
export function arrangeSeats(
  memberIds: string[],
  me: string,
  focused: BackendBet | undefined,
  cap = SEAT_CAP,
) {
  const inBet =
    focused?.status === "PENDING" && focused.picks && focused.noGuessUserIds
      ? new Set([...focused.picks.map((pick) => pick.userId), ...focused.noGuessUserIds])
      : null;
  const eligible = inBet ? memberIds.filter((id) => inBet.has(id)) : memberIds;
  const others = eligible.filter((id) => id !== me);
  const all = eligible.includes(me) ? [me, ...others] : others;
  if (all.length <= cap) return { seated: all, rail: [] as string[] };
  return { seated: all.slice(0, cap - 1), rail: all.slice(cap - 1) };
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
  bet: BackendBet | undefined,
  userId: string,
  me: string,
): { label: string; tone: SeatTone } | null {
  if (!bet) return null;
  if (bet.status === "ACTIVE") {
    if (userId !== me) return null;
    if (bet.myParticipation === "picked") return { label: bet.myPick!, tone: "pick" };
    return bet.myParticipation === "no_guess" ? { label: "No guess", tone: "pass" } : null;
  }
  const pick = bet.picks?.find((p) => p.userId === userId);
  if (!pick)
    return bet.noGuessUserIds?.includes(userId) ? { label: "No guess", tone: "pass" } : null;
  if (bet.status === "PENDING") return { label: pick.choice, tone: "pick" };
  if (bet.status === "WASHED") return { label: "Washed", tone: "washed" };
  if (bet.forFun)
    return pick.choice === bet.winningChoice
      ? { label: "Won", tone: "win" }
      : { label: "Lost", tone: "loss" };
  const delta = bet.deltasCents?.[userId] ?? 0;
  return delta >= 0
    ? { label: `Won ${formatCents(delta, true)}`, tone: "win" }
    : { label: `Lost ${formatCents(-delta)}`, tone: "loss" };
}

/** Open bets for the deck, newest first. */
export function openBets(bets: BackendBet[]) {
  return bets
    .filter((bet) => bet.status === "ACTIVE" || bet.status === "PENDING")
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

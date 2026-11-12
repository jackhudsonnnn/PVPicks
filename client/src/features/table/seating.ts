import { formatPts } from "@/features/bets/ledger";
import { PASS, type Bet } from "@/features/bets/types";

/** Seats around the felt. With more members than seats, one seat becomes an "N others" spot. */
const SEAT_CAP = 8;

/**
 * Only participants in the selected open bet take seats (including Pass).
 * Without an open bet, everyone is eligible. You take the bottom seat only
 * when eligible; remaining people keep table order, never join order.
 */
export function arrangeSeats(memberIds: string[], me: string, focused?: Bet, cap = SEAT_CAP) {
  const open = focused?.status === "active" || focused?.status === "pending";
  const inBet = new Set(focused?.participants.map((p) => p.userId) ?? []);
  const eligible = memberIds.filter((id) => !open || inBet.has(id));
  const others = eligible.filter((id) => id !== me);
  const all = eligible.includes(me) ? [me, ...others] : others;
  // When everyone fits, fill every seat. Otherwise the last seat becomes the "N others" spot.
  if (all.length <= cap) return { seated: all, rail: [] as string[] };
  return { seated: all.slice(0, cap - 1), rail: all.slice(cap - 1) };
}

/** Seat centers on the table's oval edge, as % of the felt box. Seat 0 sits at the bottom. */
export function seatPosition(index: number, count: number) {
  const angle = ((90 + (index * 360) / count) * Math.PI) / 180;
  return { left: 50 + 50 * Math.cos(angle), top: 50 + 50 * Math.sin(angle) };
}

export type SeatTone = "in" | "pass" | "pick" | "win" | "loss" | "washed";

/** What a seat's chip says for the focused bet. Picks stay hidden while a bet is Active. */
export function seatStatus(
  bet: Bet | undefined,
  userId: string,
  me: string,
): { label: string; tone: SeatTone } | null {
  if (!bet) return null;
  const p = bet.participants.find((x) => x.userId === userId);
  if (!p) return null;
  if (p.selection === PASS) return { label: "No guess", tone: "pass" };
  switch (bet.status) {
    case "active":
      return userId === me ? { label: p.selection, tone: "pick" } : { label: "In", tone: "in" };
    case "pending":
      return { label: p.selection, tone: "pick" };
    case "resolved": {
      // 4fun moves no points, so the chip just says who called it.
      if (bet.forFun)
        return p.selection === bet.winningChoice
          ? { label: "Won", tone: "win" }
          : { label: "Lost", tone: "loss" };
      const d = bet.deltas?.[userId] ?? 0;
      return d > 0
        ? { label: `Won ${formatPts(d, true)}`, tone: "win" }
        : { label: `Lost ${formatPts(Math.abs(d))}`, tone: "loss" };
    }
    case "washed":
      return { label: "Washed", tone: "washed" };
  }
}

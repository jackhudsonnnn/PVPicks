import { useQuery, useQueryClient } from "@tanstack/react-query";
import { formatCents } from "@/lib/money";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import type { Bet } from "./api";
import type { User } from "@/features/people";
import { getJackblackGame, type JackblackHand, type JackblackSeat } from "./jackblack";

/**
 * The game as the viewer sees it, shared by the ticket, the felt and Details (one cache entry). Refreshes with the
 * table's bet feed, and every few seconds while it's live as a fallback.
 */
export function useJackblackGame(bet: Bet, user: User, live: boolean, enabled = true) {
  const key = userKey(user.id, "jackblack", bet.id);
  const queryClient = useQueryClient();
  useRealtimeFeed(live && enabled ? tableBetsFeed(bet.tableId) : null, () => {
    void queryClient.invalidateQueries({ queryKey: key });
  });
  return useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getJackblackGame(bet.id, signal),
    enabled,
    refetchInterval: live ? 4000 : false,
    retry: false,
  });
}

export const RESULT: Record<NonNullable<JackblackHand["result"]>, string> = {
  win: "Win",
  blackjack: "Blackjack!",
  push: "Push",
  lose: "Lose",
  bust: "Bust",
};

export function signed(cents: number) {
  return `${cents > 0 ? "+" : ""}${formatCents(cents)}`;
}

/** A hand's value as a label: "Bust", "Blackjack", "soft 17" or "17". */
export function handLabel(hand: JackblackHand, split: boolean) {
  if (hand.result) return RESULT[hand.result];
  if (hand.total > 21) return "Bust";
  if (hand.cards.length === 2 && hand.total === 21 && !split) return "Blackjack";
  return hand.soft && hand.total < 21 ? `soft ${hand.total}` : String(hand.total);
}

/** Every hand of a seat, " / " between splits. */
export function seatTotals(seat: JackblackSeat) {
  return seat.hands.map((h) => handLabel(h, seat.hands.length > 1)).join(" / ");
}

export type SeatChip = { label: string; tone: "pick" | "pass" | "win" | "loss" | "washed" };

function result(cents: number): SeatChip {
  if (cents === 0) return { label: "Even", tone: "pass" };
  return cents > 0
    ? { label: `Up ${formatCents(cents, true)}`, tone: "win" }
    : { label: `Down ${formatCents(-cents)}`, tone: "loss" };
}

/**
 * What each player's chip on the felt says about a Jackblack game: "In" while the join window is open; during a
 * round "Dealing" (with the dealer's total once turned) for the round's dealer, and each seat's total; at the end,
 * each player's result. Null for anyone not in it.
 */
export function useJackblackSeatChips(bet: Bet | undefined, user: User) {
  const live = !!bet && (bet.status === "ACTIVE" || bet.status === "PENDING");
  const data = useJackblackGame(
    bet ?? ({ id: "", tableId: "" } as Bet),
    user,
    live,
    bet?.modeId === "jackblack",
  ).data;
  return (userId: string): SeatChip | null => {
    if (!bet || bet.modeId !== "jackblack" || !data) return null;
    if (data.phase === "waiting")
      return data.players?.includes(userId) ? { label: "In", tone: "pick" } : null;
    if (!data.order?.includes(userId)) return null;
    if (data.phase === "done") return result(data.totals?.[userId] ?? 0);
    if (userId === data.dealerId)
      return {
        label:
          data.dealerTotal === undefined
            ? "Dealing"
            : `Dealer · ${data.dealerTotal > 21 ? "bust" : data.dealerTotal}`,
        tone: "pick",
      };
    const seat = data.seats?.find((s) => s.userId === userId);
    if (!seat) return null;
    const totals = seatTotals(seat);
    return {
      label: totals,
      tone: totals.split(" / ").every((t) => t === "Bust") ? "loss" : "pick",
    };
  };
}

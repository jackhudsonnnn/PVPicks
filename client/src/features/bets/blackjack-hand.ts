import { useQuery, useQueryClient } from "@tanstack/react-query";
import { formatCents } from "@/lib/money";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import type { Bet } from "./api";
import type { User } from "@/features/people";
import { getBlackjackHand, type BlackjackHandCards } from "./blackjack";

/**
 * The hand as the viewer sees it, shared by the ticket and the felt (one cache entry). Refreshes with the table's
 * bet feed, and every few seconds while it's live as a fallback.
 */
export function useBlackjackHand(bet: Bet, user: User, live: boolean, enabled = true) {
  const key = userKey(user.id, "blackjack", bet.id);
  const queryClient = useQueryClient();
  useRealtimeFeed(live && enabled ? tableBetsFeed(bet.tableId) : null, () => {
    void queryClient.invalidateQueries({ queryKey: key });
  });
  return useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getBlackjackHand(bet.id, signal),
    enabled,
    refetchInterval: live ? 4000 : false,
    retry: false,
  });
}

export const RESULT: Record<NonNullable<BlackjackHandCards["result"]>, string> = {
  win: "Win",
  blackjack: "Blackjack!",
  push: "Push",
  lose: "Lose",
  bust: "Bust",
};

export function signed(cents: number) {
  return `${cents > 0 ? "+" : ""}${formatCents(cents)}`;
}

export type SeatChip = { label: string; tone: "pick" | "pass" | "win" | "loss" | "washed" };

/**
 * What each seat's chip on the felt says about a Blackjack hand, instead of cards: "Seated" while the join window is
 * open, then each player's total ("17", "Bust", "Blackjack") and finally their result; the dealer's seat says
 * "Dealer", with their total once the hole card is turned. Null for anyone not in the hand.
 */
export function useBlackjackSeatChips(bet: Bet | undefined, user: User) {
  const live = !!bet && (bet.status === "ACTIVE" || bet.status === "PENDING");
  const data = useBlackjackHand(
    bet ?? ({ id: "", tableId: "" } as Bet),
    user,
    live,
    bet?.modeId === "blackjack",
  ).data;
  return (userId: string): SeatChip | null => {
    if (!bet || bet.modeId !== "blackjack" || !data) return null;
    if (userId === bet.proposerId) {
      if (
        data.phase === "done" &&
        data.dealerDeltaCents !== undefined &&
        data.dealerDeltaCents !== 0
      )
        return data.dealerDeltaCents > 0
          ? { label: `Won ${formatCents(data.dealerDeltaCents, true)}`, tone: "win" }
          : { label: `Lost ${formatCents(-data.dealerDeltaCents)}`, tone: "loss" };
      if (data.dealerTotal !== undefined)
        return {
          label: `Dealer · ${data.dealerTotal > 21 ? "bust" : data.dealerTotal}`,
          tone: "pick",
        };
      return { label: "Dealer", tone: "pick" };
    }
    const seat = data.seats.find((s) => s.userId === userId);
    if (!seat) return null;
    if (!seat.hands) return { label: "Seated", tone: "pick" };
    if (seat.deltaCents !== undefined) {
      if (seat.deltaCents === 0) return { label: "Push", tone: "pass" };
      return seat.deltaCents > 0
        ? { label: `Won ${formatCents(seat.deltaCents, true)}`, tone: "win" }
        : { label: `Lost ${formatCents(-seat.deltaCents)}`, tone: "loss" };
    }
    const totals = seat.hands.map((h) =>
      h.total > 21
        ? "Bust"
        : h.cards.length === 2 && h.total === 21 && seat.hands!.length === 1
          ? "Blackjack"
          : String(h.total),
    );
    const busted = totals.every((t) => t === "Bust");
    return { label: totals.join(" / "), tone: busted ? "loss" : "pick" };
  };
}

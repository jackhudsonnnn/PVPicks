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

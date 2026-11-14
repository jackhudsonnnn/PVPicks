import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRealtimeFeed } from "@/lib/realtime";
import { tickerFeed } from "@/lib/realtime-connection";
import { getTicker } from "./api";
import { userKey } from "@/lib/query-keys";

/**
 * Today's games (live first), shared by the score strip, the proposal sheet's game picker and tickets. Refreshes on
 * the realtime ticker feed and once a minute as a fallback; null data means scores are unavailable.
 */
export function useTicker(userId: string) {
  const queryClient = useQueryClient();
  const key = userKey(userId, "ticker");
  useRealtimeFeed(tickerFeed, () => void queryClient.invalidateQueries({ queryKey: key }));
  return useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getTicker(signal),
    retry: false,
    refetchInterval: 60_000,
  });
}

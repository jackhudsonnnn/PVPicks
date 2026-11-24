import { useQuery } from "@tanstack/react-query";
import { getBetLiveStats } from "./api";

/** Refresh while the game is live: stats arrive every few seconds, scores every 15. */
const LIVE_REFRESH_MS = 15_000;

/**
 * What a sports bet follows (a score, a margin, each player's stat) when proposed, at lock and now; the ticket's
 * progress bar and Details share it. It stops refreshing once the game isn't live or the bet is called.
 */
export function useBetLiveStats(betId: string, enabled = true) {
  return useQuery({
    queryKey: ["bet-live", betId],
    queryFn: ({ signal }) => getBetLiveStats(betId, signal),
    enabled,
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.status === "LIVE" && !query.state.data.closed ? LIVE_REFRESH_MS : false,
  });
}

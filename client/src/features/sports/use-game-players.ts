import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { getGamePlayers } from "./api";

/** A game's players and current stats, for proposing a player-mode bet; refreshed every 30 s while shown. */
export function useGamePlayers(userId: string, gameId: string | null) {
  return useQuery({
    queryKey: userKey(userId, "game-players", gameId ?? ""),
    queryFn: ({ signal }) => getGamePlayers(gameId ?? "", signal),
    enabled: gameId !== null,
    retry: false,
    refetchInterval: 30_000,
  });
}

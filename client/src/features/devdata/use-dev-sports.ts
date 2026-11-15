import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { getDemo, getDevSports } from "./api";

/** The dev data list, refreshed every few seconds while shown so chunk positions move. */
export function useDevSports(userId: string, live = false) {
  return useQuery({
    queryKey: userKey(userId, "dev-sports"),
    queryFn: ({ signal }) => getDevSports(signal),
    retry: false,
    refetchInterval: live ? 3000 : false,
  });
}

/** The demo world's size and the bots' activity, or null when the backend has no demo. `live` refreshes it. */
export function useDemo(userId: string, live = false) {
  return useQuery({
    queryKey: userKey(userId, "dev-demo"),
    queryFn: ({ signal }) => getDemo(signal),
    retry: false,
    refetchInterval: live ? 5000 : false,
  });
}

import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";

/**
 * Query keys for dev data: per user when signed in (cleared on sign-out with the rest of the user's data), and
 * a separate "dev" key signed out, so the signed-out checks never sit under a user's keys.
 */
export function devKey(scope: string, name: string) {
  return scope === "guest" ? ["dev", name] : userKey(scope, name);
}
import { getDemo, getDevSports } from "./api";

/** The dev data list, refreshed every few seconds while shown so chunk positions move. */
export function useDevSports(userId: string, live = false) {
  return useQuery({
    queryKey: devKey(userId, "dev-sports"),
    queryFn: ({ signal }) => getDevSports(signal),
    retry: false,
    refetchInterval: live ? 3000 : false,
  });
}

/** The demo world's size and the bots' activity, or null when the backend has no demo. `live` refreshes it. */
export function useDemo(userId: string, live = false) {
  return useQuery({
    queryKey: devKey(userId, "dev-demo"),
    queryFn: ({ signal }) => getDemo(signal),
    retry: false,
    refetchInterval: live ? 5000 : false,
  });
}

/**
 * Whether this is a dev backend (dev sports data or the demo answers), for the Dev and docs icons. Works signed
 * out: `scope` is the user's id, or "guest".
 */
export function useDevAvailable(scope: string) {
  const sports = useDevSports(scope);
  const demo = useDemo(scope);
  return !!(sports.data || demo.data);
}

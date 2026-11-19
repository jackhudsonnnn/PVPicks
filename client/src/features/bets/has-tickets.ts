import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { listMyBets } from "./api";

/**
 * Whether there's at least one ticket (a bet at one of your tables) to show on the Tickets page: one bet from
 * `GET /me/bets?limit=1`. Unknown counts as yes, so the Tickets links don't flash disabled while it loads or after
 * an error. Lives under the "my-bets" key, so anything that refreshes your bets refreshes this too.
 */
export function useHasTickets(userId: string): boolean {
  const query = useQuery({
    queryKey: userKey(userId, "my-bets", "any"),
    queryFn: ({ signal }) => listMyBets({ limit: 1 }, signal),
    retry: false,
  });
  return query.data ? query.data.items.length > 0 : true;
}

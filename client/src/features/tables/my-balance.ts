import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { getMyBalance } from "./api";

/** Where your overall balance is cached; the personal feed's `balance.changed` and the settlements feed refresh it. */
export const myBalanceKey = (userId: string) => userKey(userId, "balance");

/** Your balance across every table plus the net of your open settle-ups (`GET /me/balance`), for the points pill. */
export function useMyBalance(userId: string) {
  return useQuery({
    queryKey: myBalanceKey(userId),
    queryFn: ({ signal }) => getMyBalance(signal),
    retry: false,
  });
}

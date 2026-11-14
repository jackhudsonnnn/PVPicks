import { useEffect, useMemo } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { listMyBets, type Bet } from "./api";

/** Tables where you have a real pick (not No guess) in an Active bet: their cards show green. */
export function inPlayTableIds(bets: Bet[]): ReadonlySet<string> {
  return new Set(
    bets
      .filter((bet) => bet.status === "ACTIVE" && bet.myParticipation === "picked")
      .map((bet) => bet.tableId),
  );
}

/**
 * Tables you're in play at, from every page of `GET /me/bets?status=ACTIVE` (pick windows are short, so there are
 * few). Lives under the "my-bets" key, so anything that refreshes your bets refreshes this too.
 */
export function useInPlayTables(userId: string): ReadonlySet<string> {
  const query = useInfiniteQuery({
    queryKey: userKey(userId, "my-bets", "in-play"),
    queryFn: ({ pageParam, signal }) =>
      listMyBets({ status: "ACTIVE", ...(pageParam ? { cursor: pageParam } : {}) }, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query;
  // Keyed on the page count too: a page that arrives at once leaves the flags unchanged.
  const pages = query.data?.pages.length ?? 0;
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [pages, hasNextPage, isFetchingNextPage, fetchNextPage]);
  return useMemo(
    () => inPlayTableIds(query.data?.pages.flatMap((page) => page.items) ?? []),
    [query.data],
  );
}

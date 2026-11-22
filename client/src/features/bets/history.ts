import { useEffect } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { getBet, getMemberRecord, listMemberBets, type Bet } from "./api";

/**
 * Reads beyond a table's live bets. Every key sits under the table's bets key, so the table's bets feed and bet
 * actions refresh them along with the live list.
 */
const tableBetsKey = (userId: string, tableId: string) => userKey(userId, tableId, "bets");

/** A bet by id: from the live list when it's there, otherwise fetched (older bets opened from chat or profiles). */
export function useBet(
  userId: string,
  tableId: string,
  betId: string | null,
  live: Bet[] | undefined,
  expire: () => void,
): Bet | undefined {
  const fromLive = betId ? live?.find((bet) => bet.id === betId) : undefined;
  const query = useQuery({
    queryKey: [...tableBetsKey(userId, tableId), "bet", betId ?? ""],
    queryFn: ({ signal }) => getBet(tableId, betId!, signal),
    enabled: !!betId && !!live && !fromLive,
    retry: false,
  });
  useExpireOnUnauthorized([query.error], expire);
  return fromLive ?? (betId ? query.data : undefined);
}

/** A member's record at the table. */
export function useMemberRecord(
  userId: string,
  tableId: string,
  memberId: string,
  expire: () => void,
) {
  const query = useQuery({
    queryKey: [...tableBetsKey(userId, tableId), "member", memberId, "record"],
    queryFn: ({ signal }) => getMemberRecord(tableId, memberId, signal),
    retry: false,
  });
  useExpireOnUnauthorized([query.error], expire);
  return query;
}

/** A member's bets at the table, newest first, a page at a time. */
export function useMemberBets(
  userId: string,
  tableId: string,
  memberId: string,
  expire: () => void,
) {
  const query = useInfiniteQuery({
    queryKey: [...tableBetsKey(userId, tableId), "member", memberId, "bets"],
    queryFn: ({ pageParam, signal }) => listMemberBets(tableId, memberId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  useExpireOnUnauthorized([query.error], expire);
  return query;
}

/**
 * Keeps fetching pages until there are none left. Keyed on the page count too: a page that arrives at once leaves
 * the flags unchanged, so the effect wouldn't otherwise run again.
 */
export function useFetchAll(query: {
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  fetchNextPage: () => unknown;
  data?: { pages: unknown[] } | undefined;
}) {
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query;
  const pages = query.data?.pages.length ?? 0;
  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [pages, hasNextPage, isFetchingNextPage, fetchNextPage]);
}

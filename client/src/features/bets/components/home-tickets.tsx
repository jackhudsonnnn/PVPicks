import { useEffect, useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { EmptyState } from "@/components/empty-state";
import { LoadMore } from "@/components/load-more";
import { ScrollContainer } from "@/components/scroll-container";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { useMediaQuery } from "@/lib/use-media-query";
import { cn } from "@/lib/utils";
import type { User } from "@/features/people";
import { listTables, useNewcomer } from "@/features/tables";
import { FindFriendsDialog } from "@/components/find-friends-dialog";
import { useBetActions } from "@/features/bets/actions";
import { listMyBets } from "@/features/bets/api";
import { FIRST_TICKET_PAGE, nextTicketPage } from "@/features/bets/ticket-feed";
import { ActionStatus } from "@/components/action-status";
import { BetTicket } from "./ticket";
import { LoadingList } from "@/components/loading";

/** Fill the column before waiting for a scroll: a status with few bets shouldn't leave it nearly empty. */
const MIN_SHOWN = 6;

/**
 * Home's Tickets: every bet at your tables, live first, then pending, resolved and washed (newest first within
 * each), loading more as you scroll. Beside the tables (from `lg`) it's a box as tall as the tables box that scrolls
 * inside; stacked below them on a tall tablet it's part of the page. Table cards refresh it when a table's bets
 * change.
 */
export function HomeTickets({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const bets = useInfiniteQuery({
    queryKey: userKey(user.id, "my-bets", "home"),
    queryFn: ({ pageParam, signal }) =>
      listMyBets(
        { status: pageParam.status, ...(pageParam.cursor ? { cursor: pageParam.cursor } : {}) },
        signal,
      ),
    initialPageParam: FIRST_TICKET_PAGE,
    getNextPageParam: (page, _pages, param) => nextTicketPage(page, param),
    retry: false,
  });
  const actions = useBetActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  useExpireOnUnauthorized([tables.error, bets.error], expire);
  const shown = bets.data?.pages.flatMap((page) => page.items) ?? [];
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = bets;
  // Keyed on the page count too: an empty page that arrives at once changes nothing else.
  const pages = bets.data?.pages.length ?? 0;
  useEffect(() => {
    if (shown.length < MIN_SHOWN && hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [pages, shown.length, hasNextPage, isFetchingNextPage, fetchNextPage]);
  const byId = new Map((tables.data ?? []).map((t) => [t.id, t]));
  const peopleKey = userKey(user.id, "people");
  const beside = useMediaQuery("(min-width: 1024px)");
  const newcomer = useNewcomer(user.id);
  const [findFriends, setFindFriends] = useState(false);

  return (
    <section className="min-w-0 space-y-3" aria-labelledby="home-tickets-heading">
      <div className="flex items-center justify-between">
        <h2
          id="home-tickets-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Tickets
        </h2>
        {/* Same button as "New table" beside it, so the two headers line up. */}
        {newcomer ? (
          <Button
            variant="outline"
            className="text-primary hover:text-primary"
            onClick={() => setFindFriends(true)}
          >
            All tickets <ArrowRight />
          </Button>
        ) : (
          <Button asChild variant="outline" className="text-primary hover:text-primary">
            <Link to="/tickets">
              All tickets <ArrowRight />
            </Link>
          </Button>
        )}
        <FindFriendsDialog open={findFriends} onOpenChange={setFindFriends} />
      </div>
      <ActionStatus
        actions={actions}
        pendingText="A bet action may not have been saved."
        checkText="Check the table's bets and your pick first."
      />
      {bets.isPending ? (
        <LoadingList label="Loading tickets" rows={3} rowClassName="h-28" />
      ) : bets.isError ? (
        <p role="alert">{backendErrorMessage(bets.error)}</p>
      ) : shown.length === 0 && !hasNextPage ? (
        <EmptyState boxed className="p-6">
          No tickets yet. Head to a table and propose one.
        </EmptyState>
      ) : (
        // Beside the tables (from `lg`): a box as tall as the tables box (tables/components/table-cards.tsx)
        // that scrolls inside. Stacked below them on a tall tablet: the tickets are simply on the page, and the
        // page scrolls.
        <ScrollContainer
          isVisible={false}
          aria-label="Tickets"
          className={cn(
            "space-y-4",
            beside
              ? "h-[27rem] rounded-2xl border p-2 md:scrollbar-thin"
              : "overflow-x-visible overflow-y-visible",
          )}
        >
          <AnimatedList>
            {shown.map((bet) => {
              const table = byId.get(bet.tableId);
              return table ? (
                <AnimatedItem key={bet.id} className="min-w-0">
                  <BetTicket
                    bet={bet}
                    table={table}
                    user={user}
                    expire={expire}
                    peopleKey={peopleKey}
                    actions={actions}
                    className="max-w-none"
                  />
                </AnimatedItem>
              ) : null;
            })}
          </AnimatedList>
          {hasNextPage && shown.length >= MIN_SHOWN && (
            <LoadMore loading={isFetchingNextPage} onLoad={() => void fetchNextPage()} />
          )}
        </ScrollContainer>
      )}
    </section>
  );
}

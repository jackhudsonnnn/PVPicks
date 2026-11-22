import { useState } from "react";
import { useInfiniteQuery, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { backendErrorMessage } from "@/lib/api-client";
import { listMyBets, type Bet } from "@/features/bets/api";
import { STATUS_ORDER, STATUSES } from "@/features/bets/statuses";
import { listTables } from "@/features/tables";
import type { User } from "@/features/people";
import { ActionStatus } from "@/components/action-status";
import { BetTicket } from "./ticket";
import { ticketTab } from "@/features/bets/ticket-tabs";
import { useBetActions } from "@/features/bets/actions";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { LoadingList, Spinner } from "@/components/loading";

type Status = Bet["status"];

const TABS = STATUS_ORDER.map((id) => ({ ...STATUSES[id], id }));

/** Refreshes the ticket list when a table's bets change. One listener per table you're at. */
function TableBetsListener({ tableId, onChange }: { tableId: string; onChange: () => void }) {
  useRealtimeFeed(tableBetsFeed(tableId), onChange);
  return null;
}

/** Every bet at your tables by status, newest first; `?table=` narrows it to one table. */
export function TicketsPage({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const requested = useRouterState({
    select: (state) => (state.location.search as { table?: string } | undefined)?.table,
  });
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const tableId = tables.data?.some((t) => t.id === requested) ? requested : undefined;
  const [picked, setTab] = useState<Status>("ACTIVE");
  // One ticket per status says which tabs have any; empty ones are disabled, and if yours empties (your last
  // Active ticket locks) you move to the next one with tickets. Refreshed with the list (same "my-bets" prefix).
  const probes = useQueries({
    queries: STATUS_ORDER.map((status) => ({
      queryKey: userKey(user.id, "my-bets", tableId ?? "all", status, "any"),
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        listMyBets({ status, limit: 1, ...(tableId ? { tableId } : {}) }, signal),
      retry: false,
    })),
  });
  const empty = new Set(STATUS_ORDER.filter((_, i) => probes[i]!.data?.items.length === 0));
  const tab = ticketTab(STATUS_ORDER, picked, empty);
  if (tab !== picked) setTab(tab);
  const key = userKey(user.id, "my-bets", tableId ?? "all", tab);
  const bets = useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam, signal }) =>
      listMyBets(
        {
          status: tab,
          ...(tableId ? { tableId } : {}),
          ...(pageParam ? { cursor: pageParam } : {}),
        },
        signal,
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
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
  const refresh = () =>
    void queryClient.invalidateQueries({ queryKey: userKey(user.id, "my-bets") });
  const peopleKey = userKey(user.id, "people");
  const shown = bets.data?.pages.flatMap((page) => page.items) ?? [];
  const byId = new Map((tables.data ?? []).map((t) => [t.id, t]));

  return (
    <div className="space-y-6">
      {(tables.data ?? []).map((t) => (
        <TableBetsListener key={t.id} tableId={t.id} onChange={refresh} />
      ))}
      <div className="space-y-6">
        <h1 className="sr-only">Tickets</h1>
        <SegmentedTabs
          ariaLabel="Ticket status"
          size="sm"
          fitContent
          tabs={TABS.map((t) => ({
            id: t.id,
            label: t.label,
            disabled: t.id !== tab && empty.has(t.id),
          }))}
          value={tab}
          onChange={setTab}
        />
        {/* The tab's hint and which table: stacked on phones (filter first, full width), one row on bigger
            screens with the hint on the left and a fixed-width filter on the right. */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
          <p className="order-last text-sm text-muted-foreground sm:order-none">
            {TABS.find((t) => t.id === tab)!.hint}
          </p>
          {(tables.data?.length ?? 0) > 1 && (
            <SegmentedTabs
              className="w-full shrink-0 sm:w-64"
              ariaLabel="Filter by table"
              size="sm"
              dropdownOnly
              tabs={[
                { id: "all", label: "All" },
                ...(tables.data ?? []).map((t) => ({ id: t.id, label: t.name })),
              ]}
              value={tableId ?? "all"}
              onChange={(id) => {
                void navigate({
                  to: "/tickets",
                  search: id === "all" ? {} : { table: id },
                  replace: true,
                });
              }}
            />
          )}
        </div>
      </div>
      <ActionStatus
        actions={actions}
        pendingText="A bet action may not have been saved."
        checkText="Check the table's bets and your pick first."
      />
      {bets.isPending ? (
        <LoadingList label="Loading tickets" rows={4} rowClassName="h-28" />
      ) : bets.isError ? (
        <div className="space-y-3">
          <p role="alert">{backendErrorMessage(bets.error)}</p>
          <Button variant="outline" onClick={() => void bets.refetch()}>
            Try again
          </Button>
        </div>
      ) : shown.length === 0 ? (
        <EmptyState boxed>Nothing here yet.</EmptyState>
      ) : (
        <div className="space-y-4">
          <div className="gap-4 [columns:20rem_2]">
            <AnimatedList key={key.join(":")}>
              {shown.map((bet) => {
                const table = byId.get(bet.tableId);
                return table ? (
                  <AnimatedItem key={bet.id} className="mb-4 min-w-0 break-inside-avoid">
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
          </div>
          {bets.hasNextPage && (
            <Button
              variant="outline"
              className="w-full"
              disabled={bets.isFetchingNextPage}
              onClick={() => void bets.fetchNextPage()}
            >
              {bets.isFetchingNextPage && <Spinner />} Load more
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

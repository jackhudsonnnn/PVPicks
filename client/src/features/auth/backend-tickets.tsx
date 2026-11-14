import { useEffect, useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import {
  backendErrorMessage,
  BackendError,
  listMyBets,
  listTables,
  type BackendBet,
  type BackendUser,
} from "./backend-api";
import { BetMutationStatus } from "./backend-bet-controls";
import { BetTicket } from "./backend-ticket";
import { useBetActions } from "./bet-actions";
import { userKey } from "./query-keys";
import { useRealtimeFeed } from "./realtime";
import { tableBetsFeed } from "./realtime-connection";

type Status = BackendBet["status"];

const TABS: { id: Status; label: string; hint: string }[] = [
  { id: "ACTIVE", label: "Active", hint: "Timer running, you can still change your pick." },
  { id: "PENDING", label: "Pending", hint: "Wagers are in the pot. Waiting on the result." },
  { id: "SETTLED", label: "Resolved", hint: "A winning choice emerged and winners got paid." },
  { id: "WASHED", label: "Washed", hint: "No contest, everyone got their points back." },
];

/** Refreshes the ticket list when a table's bets change. One listener per table you're at. */
function TableBetsListener({ tableId, onChange }: { tableId: string; onChange: () => void }) {
  useRealtimeFeed(tableBetsFeed(tableId), onChange);
  return null;
}

/** Every bet at your tables by status, newest first; `?table=` narrows it to one table. */
export function TicketsPage({ user, expire }: { user: BackendUser; expire: () => void }) {
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
  // null until you pick a tab: opens on the first non-empty status.
  const [picked, setPicked] = useState<Status | null>(null);
  const [counts, setCounts] = useState<Record<Status, number> | null>(null);
  const tab = picked ?? TABS.find((t) => (counts?.[t.id] ?? 0) > 0)?.id ?? "ACTIVE";
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
  const latest = bets.data?.pages[0]?.counts;
  useEffect(() => {
    if (latest) setCounts(latest);
  }, [latest]);
  const actions = useBetActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const unauthorized = [tables.error, bets.error].some(
    (error) => error instanceof BackendError && error.status === 401,
  );
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
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
        <PageHeader
          eyebrow="Bet lifecycle"
          title="Tickets"
          action={
            (tables.data?.length ?? 0) > 1 && (
              <SegmentedTabs
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
                  setPicked(null);
                  setCounts(null);
                }}
              />
            )
          }
        />
        <SegmentedTabs
          ariaLabel="Ticket status"
          size="sm"
          fitContent
          tabs={TABS.map((t) => ({ id: t.id, label: t.label, count: counts?.[t.id] ?? 0 }))}
          value={tab}
          onChange={setPicked}
        />
        <p className="text-sm text-muted-foreground">{TABS.find((t) => t.id === tab)!.hint}</p>
      </div>
      <BetMutationStatus actions={actions} />
      {bets.isPending ? (
        <p>Loading tickets…</p>
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
          <div className="columns-1 gap-4 sm:columns-2">
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
              {bets.isFetchingNextPage ? "Loading…" : "Load more"}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

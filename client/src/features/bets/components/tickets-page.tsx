import { useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { backendErrorMessage } from "@/lib/api-client";
import { listMyBets, type Bet } from "@/features/bets/api";
import { listTables } from "@/features/tables";
import type { User } from "@/features/people";
import { BetMutationStatus } from "./bet-controls";
import { BetTicket } from "./ticket";
import { useBetActions } from "@/features/bets/actions";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";

type Status = Bet["status"];

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
  const [tab, setTab] = useState<Status>("ACTIVE");
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
                }}
              />
            )
          }
        />
        <SegmentedTabs
          ariaLabel="Ticket status"
          size="sm"
          fitContent
          tabs={TABS.map((t) => ({ id: t.id, label: t.label }))}
          value={tab}
          onChange={setTab}
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

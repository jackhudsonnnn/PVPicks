import { EmptyState } from "@/components/empty-state";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useCallback, useState } from "react";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { PageHeader } from "@/components/page-header";
import { myTables, useAppStore } from "@/features/store";
import { BetTicket } from "@/features/bets/components/bet-ticket";
import type { BetStatus } from "@/features/bets/types";
import { AutoPageSentinel } from "@/components/auto-page-sentinel";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";

export const Route = createFileRoute("/tickets")({
  validateSearch: (s: Record<string, unknown>): { table?: string } =>
    typeof s["table"] === "string" ? { table: s["table"] } : {},
  head: () => ({
    meta: [
      { title: "My Tickets | P2Picks" },
      {
        name: "description",
        content: "Every bet ticket across your tables: active, pending, resolved and washed.",
      },
      { property: "og:title", content: "My Tickets | P2Picks" },
      {
        property: "og:description",
        content: "Every bet ticket across your tables: active, pending, resolved and washed.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: TicketsPage,
});

const PAGE = 24;

type TicketFilter = BetStatus;

const TABS: { id: TicketFilter; label: string; hint: string }[] = [
  { id: "active", label: "Active", hint: "Timer running, you can still change your pick." },
  { id: "pending", label: "Pending", hint: "Wagers are in the pot. Waiting on the result." },
  { id: "resolved", label: "Resolved", hint: "A winning choice emerged and winners got paid." },
  { id: "washed", label: "Washed", hint: "No contest, everyone got their points back." },
];

function TicketsPage() {
  const { state } = useAppStore();
  // null until you pick a tab: opens on the first non-empty status (Active, Pending, Resolved, Washed).
  const [picked, setPicked] = useState<TicketFilter | null>(null);
  // Long histories (Pro demo has hundreds) render a page at a time.
  // TODO: cursor-page from the server once tickets live in a backend.
  const [limit, setLimit] = useState(PAGE);
  const loadMore = useCallback(() => setLimit((n) => n + PAGE), []);
  // Table filter lives in the URL (?table=...) so ticket dialogs can deep-link here
  // pre-filtered. Filtering is a cheap in-memory pass, and a narrower filter renders
  // fewer cards, so it only ever helps performance.
  const navigate = useNavigate();
  const { table: tableParam } = Route.useSearch();
  const tables = myTables(state);
  const tableIds = new Set(tables.map((t) => t.id));
  const table = tableParam && tableIds.has(tableParam) ? tableParam : "all";
  const setTable = (t: string) => {
    void navigate({
      to: "/tickets",
      search: t === "all" ? {} : { table: t },
      replace: true,
    });
  };
  const mine = state.bets.filter((b) => tableIds.has(b.tableId) && (table === "all" || b.tableId === table));
  const counts = Object.fromEntries(TABS.map((t) => [t.id, mine.filter((b) => b.status === t.id).length])) as Record<
    TicketFilter,
    number
  >;
  const tab = picked ?? TABS.find((t) => counts[t.id] > 0)?.id ?? "active";
  const shown = mine.filter((b) => b.status === tab).reverse();

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 pb-6 md:px-8 md:pb-10">
      {/* Flat solid bar, not bg-field: a second copy of the turf pattern here
          misaligns the yard lines and doubles the glow against the page. */}
      <div className="sticky top-[var(--app-top-height)] z-20 -mx-4 space-y-6 border-b bg-background px-4 pb-4 pt-8 md:-mx-8 md:px-8 md:pt-14">
        <PageHeader
          eyebrow="Bet lifecycle"
          title="Tickets"
          action={
            tables.length > 1 && (
              <SegmentedTabs
                ariaLabel="Filter by table"
                className="overflow-hidden"
                size="sm"
                fitContent
                tabs={[{ id: "all", label: "All" }, ...tables.map((t) => ({ id: t.id, label: t.name }))]}
                value={table}
                onChange={(t) => {
                  setTable(t);
                  setPicked(null);
                  setLimit(PAGE);
                }}
              />
            )
          }
        />

        <SegmentedTabs
          ariaLabel="Ticket status"
          size="sm"
          fitContent
          tabs={TABS.map((t) => ({ id: t.id, label: t.label, count: counts[t.id] }))}
          value={tab}
          onChange={(t) => {
            setPicked(t);
            setLimit(PAGE);
          }}
        />
      </div>

      {shown.length === 0 ? (
        <EmptyState boxed>Nothing here yet.</EmptyState>
      ) : (
        <div className="pb-3">
          {/* Masonry: cards pack tightly down each column so the gap under every
              card is identical; column bottoms land where they land. */}
          <div className="columns-1 gap-4 sm:columns-2 lg:columns-3">
            <AnimatedList key={`${table}:${tab}`}>
              {shown.slice(0, limit).map((b) => (
                <AnimatedItem key={b.id} className="mb-4 min-w-0 break-inside-avoid">
                  <BetTicket bet={b} />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </div>
          <AutoPageSentinel hasMore={shown.length > limit} pageKey={`${table}:${tab}:${limit}`} onLoadMore={loadMore} />
        </div>
      )}
    </div>
  );
}

import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { backendErrorMessage } from "@/lib/api-client";
import { searchTableMembers, type Table, type TableBalance } from "@/features/tables/api";
import { searchTerm } from "@/features/social";
import { type User, PersonAvatar, usePerson } from "@/features/people";

type Scenario = "settle" | "true";

const SCENARIOS: { id: Scenario; label: string; description: string }[] = [
  {
    id: "settle",
    label: "Settle",
    description:
      "P2Picks is zero sum, in that one player's loss in points is another player's gain. When the host settles the table, these values are reset to 0 and each player is shown who to send/receive money to/from.",
  },
  {
    id: "true",
    label: "True",
    description:
      "You can view this balance as the worst-case scenario, which assumes you/your peers lose every pending bet. When the host settles the table, these values decrement by your settle balance.",
  },
];

interface People {
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/** Everyone at the table with their points here, biggest winner first. */
export function PeersPanel({
  table,
  balances,
  seatedIds,
  onSelectUser,
  className,
  ...people
}: People & {
  onSelectUser: (userId: string) => void;
  table: Table;
  balances: { data?: TableBalance[] | undefined; error: unknown };
  seatedIds: string[];
  className?: string;
}) {
  const [query, setQuery] = useState("");
  const [scenario, setScenario] = useState<Scenario>("settle");
  const active = SCENARIOS.find((s) => s.id === scenario)!;
  const byId = new Map((balances.data ?? []).map((b) => [b.userId, b]));
  const rows = [...table.memberIds].sort((a, b) => {
    const x = byId.get(a)?.settleCents ?? 0n;
    const y = byId.get(b)?.settleCents ?? 0n;
    return x === y ? 0 : x < y ? 1 : -1;
  });
  // Up to 500 people: mount them 20 at a time as you scroll. A search asks the server for matching usernames, 50 at
  // a time, so only those rows (and their profiles) load.
  const rowPages = useClientPages(rows);
  const searching = query.trim() !== "";
  const term = searchTerm(query);
  const search = useInfiniteQuery({
    queryKey: [...people.peopleKey, "member-search", table.id, term ?? ""],
    queryFn: ({ pageParam, signal }) => searchTableMembers(table.id, term!, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    enabled: term !== null,
    retry: false,
  });
  const matches = search.data?.pages.flatMap((page) => page.items) ?? [];
  const shownRows = searching ? matches : rowPages.shown;
  return (
    <div className={cn("@container flex min-h-0 flex-col gap-3 p-4", className)}>
      <dl className="hidden gap-1 rounded-lg border bg-surface px-3 py-2 text-[11px] leading-snug text-muted-foreground @md:grid">
        {SCENARIOS.map((s) => (
          <div key={s.id} className="flex gap-1.5">
            <dt className="font-bold uppercase text-foreground">{s.label}</dt>
            <dd>{s.description}</dd>
          </div>
        ))}
      </dl>
      <div className="@md:hidden">
        <SegmentedTabs<Scenario>
          size="sm"
          ariaLabel="Balance view"
          tabs={SCENARIOS.map((s) => ({ id: s.id, label: s.label }))}
          value={scenario}
          onChange={setScenario}
        />
        <p className="mt-2 px-1 text-center text-[11px] leading-snug text-muted-foreground">
          {active.description}
        </p>
      </div>
      <div className="hidden grid-cols-[minmax(0,1fr)_repeat(2,4.5rem)] gap-2 px-3 font-mono text-[10px] uppercase tracking-wider text-muted-foreground @md:grid">
        <span />
        {SCENARIOS.map((s) => (
          <span key={s.id} className="text-right leading-tight">
            {s.label}
          </span>
        ))}
      </div>
      <Input
        type="search"
        aria-label="Search members"
        placeholder={`Search members (${table.memberIds.length})`}
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />
      {balances.error ? <p role="alert">{backendErrorMessage(balances.error)}</p> : null}
      {searching && term === null ? (
        <p className="text-sm text-muted-foreground">
          Usernames use up to 15 letters, numbers and underscores.
        </p>
      ) : searching && search.isError ? (
        <p role="alert">{backendErrorMessage(search.error)}</p>
      ) : searching && search.isSuccess && matches.length === 0 ? (
        <p className="text-sm text-muted-foreground">No one here matches.</p>
      ) : null}
      <ScrollContainer isVisible={false} className="-mx-2 min-h-0 flex-1 px-2">
        <ul className="grid gap-1.5">
          <AnimatedList>
            {shownRows.map((id) => (
              <PeerRow
                key={id}
                id={id}
                balance={byId.get(id)}
                seated={seatedIds.includes(id)}
                scenario={scenario}
                onSelect={() => onSelectUser(id)}
                {...people}
              />
            ))}
          </AnimatedList>
        </ul>
        {!searching && rowPages.hasMore && (
          <PageEdge onMore={rowPages.more} label="More peers" className="mt-2 w-full" />
        )}
        {searching && search.hasNextPage && !search.isFetchingNextPage && (
          <PageEdge
            onMore={() => void search.fetchNextPage()}
            label="More matches"
            className="mt-2 w-full"
          />
        )}
      </ScrollContainer>
    </div>
  );
}

function Points({ cents }: { cents: bigint | undefined }) {
  return (
    <span
      className={cn(
        "scoreboard text-lg",
        cents === undefined || cents === 0n
          ? "text-muted-foreground"
          : cents > 0n
            ? "text-win"
            : "text-loss",
      )}
    >
      {cents === undefined ? "-" : formatCents(cents, true)}
    </span>
  );
}

function PeerRow({
  id,
  balance,
  seated,
  scenario,
  onSelect,
  ...people
}: People & {
  onSelect: () => void;
  id: string;
  balance: TableBalance | undefined;
  seated: boolean;
  scenario: Scenario;
}) {
  const person = usePerson(id, people.user, people.expire, people.peopleKey);
  const value = (s: Scenario) => (s === "settle" ? balance?.settleCents : balance?.trueCents);
  return (
    <AnimatedItem as="li">
      <button
        type="button"
        onClick={onSelect}
        className="flex w-full items-center gap-3 rounded-lg border bg-surface px-3 py-2.5 text-left transition-colors hover:border-primary/60"
      >
        <PersonAvatar
          id={id}
          size="sm"
          className="ring-surface"
          user={people.user}
          expire={people.expire}
          queryKey={people.peopleKey}
        />
        <span className="min-w-0 flex-1">
          <span className="block break-words text-sm font-semibold leading-tight">
            {person?.name ?? "…"}
          </span>
          <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            {seated ? "Seated" : "Not seated"}
          </span>
        </span>
        <span className="@md:hidden">
          <Points cents={value(scenario)} />
        </span>
        <span className="hidden grid-cols-[repeat(2,4.5rem)] gap-2 text-right @md:grid">
          {SCENARIOS.map((s) => (
            <Points key={s.id} cents={value(s.id)} />
          ))}
        </span>
      </button>
    </AnimatedItem>
  );
}

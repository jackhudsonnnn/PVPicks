import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { backendErrorMessage } from "@/lib/api-client";
import { searchTableMembers, type Table, type TableBalance } from "@/features/tables/api";
import { searchTerm } from "@/features/social";
import { type User, PersonAvatar, usePerson } from "@/features/people";

interface People {
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/**
 * Everyone at the table with their balance here, biggest first. A name opens the profile; a balance opens the
 * what-if view of that member's tickets in play.
 */
export function PeersPanel({
  table,
  balances,
  onSelectUser,
  onSelectBalance,
  className,
  ...people
}: People & {
  onSelectUser: (userId: string) => void;
  onSelectBalance: (userId: string) => void;
  table: Table;
  balances: { data?: TableBalance[] | undefined; error: unknown };
  className?: string;
}) {
  const [query, setQuery] = useState("");
  const byId = new Map((balances.data ?? []).map((b) => [b.userId, b]));
  const rows = [...table.memberIds].sort((a, b) => {
    const x = byId.get(a)?.balanceCents ?? 0n;
    const y = byId.get(b)?.balanceCents ?? 0n;
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
      <p className="px-1 text-[11px] leading-snug text-muted-foreground">
        Each balance assumes every pending ticket loses. Tap one to check which win.
      </p>
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
                onSelect={() => onSelectUser(id)}
                onSelectBalance={() => onSelectBalance(id)}
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
  onSelect,
  onSelectBalance,
  ...people
}: People & {
  onSelect: () => void;
  onSelectBalance: () => void;
  id: string;
  balance: TableBalance | undefined;
}) {
  const person = usePerson(id, people.user, people.expire, people.peopleKey);
  const name = person?.name ?? "…";
  return (
    <AnimatedItem as="li" className="flex items-stretch gap-1.5">
      <button
        type="button"
        onClick={onSelect}
        aria-label={`${name}'s profile`}
        className="flex min-w-0 flex-1 items-center gap-3 rounded-lg border bg-surface px-3 py-2.5 text-left transition-colors hover:border-primary/60"
      >
        <PersonAvatar
          id={id}
          size="sm"
          className="ring-surface"
          user={people.user}
          expire={people.expire}
          queryKey={people.peopleKey}
        />
        <span className="min-w-0 flex-1 break-words text-sm font-semibold leading-tight">
          {name}
        </span>
      </button>
      <button
        type="button"
        onClick={onSelectBalance}
        aria-label={`${name}'s balance${balance ? `, ${formatCents(balance.balanceCents, true)} points` : ""}`}
        className="flex w-24 shrink-0 items-center justify-end rounded-lg border bg-surface px-3 transition-colors hover:border-primary/60"
      >
        <Points cents={balance?.balanceCents} />
      </button>
    </AnimatedItem>
  );
}

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { backendErrorMessage, type BackendTable, type BackendUser } from "./backend-api";
import { PersonAvatar } from "./backend-avatar";
import type { TableBalance } from "./tables-api";
import { usePerson } from "./use-person";

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
  user: BackendUser;
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
  table: BackendTable;
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
      <ScrollContainer isVisible={false} className="-mx-2 min-h-0 flex-1 px-2">
        <ul className="grid gap-1.5">
          <AnimatedList>
            {rows.map((id) => (
              <PeerRow
                key={id}
                id={id}
                balance={byId.get(id)}
                seated={seatedIds.includes(id)}
                scenario={scenario}
                query={query.trim().toLowerCase()}
                onSelect={() => onSelectUser(id)}
                {...people}
              />
            ))}
          </AnimatedList>
        </ul>
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
  query,
  onSelect,
  ...people
}: People & {
  onSelect: () => void;
  id: string;
  balance: TableBalance | undefined;
  seated: boolean;
  scenario: Scenario;
  query: string;
}) {
  const person = usePerson(id, people.user, people.expire, people.peopleKey);
  if (query && !person?.name.toLowerCase().includes(query)) return null;
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

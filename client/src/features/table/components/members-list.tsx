import { useState } from "react";

import { cn } from "@/lib/utils";

import { useAppStore, useMe, useGetUser } from "@/features/store";

import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";

import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { SCENARIOS, cachedPeerScenarios, type Scenario } from "../scenarios";

import { SearchInput } from "@/components/search-input";
import { SignedPoints } from "@/components/signed-points";
import { EmptyState } from "@/components/empty-state";

export function MembersList({
  table,
  seatedIds,
  onSelectUser,
  className,
}: {
  table: Table;
  seatedIds: string[];
  onSelectUser: (id: string) => void;
  className?: string;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [q, setQ] = useState("");
  const [scenario, setScenario] = useState<Scenario>("settle");
  const active = SCENARIOS.find((x) => x.id === scenario)!;
  const needle = q.trim().toLowerCase();
  const nets = cachedPeerScenarios(state.bets, table.id);
  const zero = { true: 0, settle: 0 };
  const rows = table.memberIds
    .map((id) => ({ u: getUser(id), n: nets.get(id) ?? zero, net: (nets.get(id) ?? zero).settle }))
    .filter(({ u }) => !needle || u.username.toLowerCase().includes(needle))
    .sort((a, b) => b.net - a.net);

  return (
    <div className={cn("@container flex min-h-0 flex-col gap-3 p-4", className)}>
      <dl className="hidden gap-1 rounded-lg border bg-surface px-3 py-2 text-[11px] leading-snug text-muted-foreground @md:grid">
        {SCENARIOS.map((x) => (
          <div key={x.id} className="flex gap-1.5">
            <dt className="font-bold uppercase text-foreground">{x.label}</dt>
            <dd>{x.description}</dd>
          </div>
        ))}
      </dl>
      <div className="@md:hidden">
        <SegmentedTabs<Scenario>
          size="sm"
          ariaLabel="Balance view"
          tabs={SCENARIOS.map((x) => ({ id: x.id, label: x.label }))}
          value={scenario}
          onChange={setScenario}
        />
        <p className="mt-2 px-1 text-center text-[11px] leading-snug text-muted-foreground">{active.description}</p>
      </div>
      <div className="hidden grid-cols-[minmax(0,1fr)_repeat(2,4.5rem)] gap-2 px-3 font-mono text-[10px] uppercase tracking-wider text-muted-foreground @md:grid">
        <span />
        {SCENARIOS.map((x) => (
          <span key={x.id} className="text-right leading-tight">
            {x.label}
          </span>
        ))}
      </div>
      <SearchInput
        value={q}
        onChange={setQ}
        label="Search members"
        placeholder={`Search members (${table.memberIds.length})`}
      />
      <ScrollContainer isVisible={false} className="-mx-2 min-h-0 flex-1 px-2">
        {rows.length === 0 && <EmptyState className="py-8">No one matches “{q}”.</EmptyState>}
        <ul className="grid gap-1.5">
          <AnimatedList>
            {rows.map(({ u, n }) => (
              <AnimatedItem as="li" key={u.id}>
                <button
                  onClick={() => onSelectUser(u.id)}
                  className="flex w-full items-center gap-3 rounded-lg border bg-surface px-3 py-2.5 text-left transition-colors hover:border-primary/60"
                >
                  <UserAvatar userId={u.id} size="sm" className="ring-surface" />
                  <span className="min-w-0 flex-1">
                    <span className="block break-words leading-tight text-sm font-semibold">
                      {u.id === me ? "You" : u.username}
                    </span>
                    <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                      {seatedIds.includes(u.id) ? "Seated" : "Not seated"}
                    </span>
                  </span>
                  <span className="@md:hidden">
                    <SignedPoints value={n[scenario]} className="text-lg" />
                  </span>
                  <span className="hidden grid-cols-[repeat(2,4.5rem)] gap-2 text-right @md:grid">
                    {SCENARIOS.map((x) => (
                      <SignedPoints key={x.id} value={n[x.id]} className="text-lg" />
                    ))}
                  </span>
                </button>
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ul>
      </ScrollContainer>
    </div>
  );
}

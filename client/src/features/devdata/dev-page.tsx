import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pause, Play, RotateCcw, Square } from "lucide-react";
import { toast } from "sonner";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { cn } from "@/lib/utils";
import type { User } from "@/features/people";
import { devAction, type DevKind, type DevSource } from "./api";
import { useDevSports } from "./use-dev-sports";

const minutes = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

/**
 * Dev data controls (dev backend only): start scenario files and recorded chunks, which feed `DEV` games through the
 * real sports pipeline. Scenario files are edited in the repo (`dev/sports/scenarios/`) and picked up live.
 */
export function DevPage({ user }: { user: User }) {
  const queryClient = useQueryClient();
  const data = useDevSports(user.id, true);
  const act = useMutation({
    mutationFn: (run: () => Promise<void>) => run(),
    onError: (error) => toast.error(backendErrorMessage(error)),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: userKey(user.id, "dev-sports") });
      void queryClient.invalidateQueries({ queryKey: userKey(user.id, "ticker") });
    },
  });

  if (data.isPending) return <p className="text-sm text-muted-foreground">Loading dev data…</p>;
  if (data.isError) return <p role="alert">{backendErrorMessage(data.error)}</p>;
  if (!data.data)
    return (
      <EmptyState boxed className="p-6">
        Dev data is off. Set PVPICKS_DEV_SPORTS_DIR on the backend to turn it on.
      </EmptyState>
    );
  const { scenarios, chunks, speeds } = data.data;

  return (
    <div className="space-y-8">
      <PageHeader eyebrow="Dev only" title="Dev data" />
      <p className="text-sm text-muted-foreground">
        Running sources add DEV games next to real ones, through the same pipeline: bets on them
        lock, resolve and settle like real bets. Edit scenario files in{" "}
        <code className="font-mono text-xs">dev/sports/scenarios/</code> while they run; changes
        apply within a few seconds.
      </p>
      <Section title="Scenario files" empty="No scenario files.">
        {scenarios.map((source) => (
          <SourceCard
            key={source.name}
            source={source}
            kind="scenarios"
            disabled={act.isPending}
            act={act.mutate}
          />
        ))}
      </Section>
      <Section
        title="Recorded chunks"
        empty="No chunks yet. Cut one with scripts/cut-sports-chunk.py."
      >
        {chunks.map((source) => (
          <SourceCard
            key={source.name}
            source={source}
            kind="chunks"
            speeds={speeds}
            disabled={act.isPending}
            act={act.mutate}
          />
        ))}
      </Section>
    </div>
  );
}

function Section({
  title,
  empty,
  children,
}: {
  title: string;
  empty: string;
  children: React.ReactNode[];
}) {
  return (
    <section className="space-y-3">
      <h2 className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
        {title}
      </h2>
      {children.length === 0 ? (
        <EmptyState boxed className="p-6">
          {empty}
        </EmptyState>
      ) : (
        <ul className="space-y-3">{children}</ul>
      )}
    </section>
  );
}

function SourceCard({
  source,
  kind,
  speeds,
  disabled,
  act,
}: {
  source: DevSource;
  kind: DevKind;
  speeds?: number[];
  disabled: boolean;
  act: (run: () => Promise<void>) => void;
}) {
  const run = (action: "start" | "stop" | "pause" | "resume") => () =>
    act(() => devAction(kind, source.name, action));
  return (
    <li
      className={cn(
        "space-y-2 rounded-xl border bg-surface p-4",
        source.running && "border-primary/60 bg-primary/5",
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold">
            {source.league && (
              <span className="mr-2 rounded bg-muted px-1.5 py-0.5 font-mono text-[10px] font-bold">
                {source.league}
              </span>
            )}
            {source.title}
          </p>
          <p className="font-mono text-[11px] text-muted-foreground">{source.name}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" disabled={disabled} onClick={run("start")}>
            {source.running ? <RotateCcw /> : <Play />} {source.running ? "Restart" : "Start"}
          </Button>
          {source.running && kind === "chunks" && (
            <Button
              size="sm"
              variant="outline"
              disabled={disabled}
              onClick={run(source.paused ? "resume" : "pause")}
            >
              {source.paused ? <Play /> : <Pause />} {source.paused ? "Resume" : "Pause"}
            </Button>
          )}
          {source.running && (
            <Button size="sm" variant="outline" disabled={disabled} onClick={run("stop")}>
              <Square /> Stop
            </Button>
          )}
        </div>
      </div>
      {source.description && <p className="text-sm text-muted-foreground">{source.description}</p>}
      {source.running && (
        <p className="font-mono text-xs">
          {source.games ?? 0} {source.games === 1 ? "game" : "games"}, {source.players ?? 0} players
          {kind === "chunks" &&
            source.positionSeconds !== undefined &&
            ` · ${minutes(source.positionSeconds)} of ${minutes(source.lengthSeconds ?? 0)}`}
        </p>
      )}
      {source.running && kind === "chunks" && speeds && (
        <SegmentedTabs
          size="sm"
          ariaLabel={`${source.title} speed`}
          tabs={speeds.map((speed) => ({ id: String(speed), label: `${speed}×` }))}
          value={String(source.speed ?? 1)}
          onChange={(speed) => act(() => devAction(kind, source.name, "speed", Number(speed)))}
        />
      )}
      {source.error && (
        <p role="alert" className="text-sm text-loss">
          {source.error}
        </p>
      )}
    </li>
  );
}

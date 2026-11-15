import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Pause, Play, RotateCcw, Square } from "lucide-react";
import { toast } from "sonner";
import { EmptyState } from "@/components/empty-state";
import { PageHeader } from "@/components/page-header";
import { SegmentedTabs } from "@/components/segmented-tabs";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { cn } from "@/lib/utils";
import type { User } from "@/features/people";
import {
  devAction,
  resetDemo,
  type DemoWorld,
  type DevKind,
  type DevSource,
  type DevSports,
} from "./api";
import { useDemo, useDevSports } from "./use-dev-sports";

const minutes = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

/**
 * Dev-only controls: the demo world's reset, and scenario files and recorded chunks, which feed `DEV` games through
 * the real sports pipeline. Scenario files are edited in the repo (`dev/sports/scenarios/`) and picked up live.
 */
export function DevPage({
  user,
  endSession,
}: {
  user: User;
  endSession: (message: string) => void;
}) {
  const sports = useDevSports(user.id, true);
  const demo = useDemo(user.id);

  if (sports.isPending || demo.isPending)
    return <p className="text-sm text-muted-foreground">Loading dev data…</p>;
  if (sports.isError) return <p role="alert">{backendErrorMessage(sports.error)}</p>;
  if (demo.isError) return <p role="alert">{backendErrorMessage(demo.error)}</p>;
  if (!sports.data && !demo.data)
    return (
      <EmptyState boxed className="p-6">
        Dev data is off. Set PVPICKS_DEV_SPORTS_DIR on the backend to turn it on.
      </EmptyState>
    );

  return (
    <div className="space-y-8">
      <PageHeader eyebrow="Dev only" title="Dev data" />
      {demo.data && <DemoSection world={demo.data} endSession={endSession} />}
      {sports.data && <SportsSections user={user} sports={sports.data} />}
    </div>
  );
}

function DemoSection({
  world,
  endSession,
}: {
  world: DemoWorld;
  endSession: (message: string) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const reset = useMutation({
    mutationFn: resetDemo,
    onSuccess: () => endSession("The demo world was reset. Pick an account."),
    onError: (error) => toast.error(backendErrorMessage(error)),
  });
  return (
    <section className="space-y-3">
      <h2 className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
        Demo world
      </h2>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-surface p-4">
        <p className="text-sm">
          {world.users} people, {world.tables} tables, {world.follows} follows, {world.blocks}{" "}
          blocks.
          <span className="block text-muted-foreground">
            Reset wipes everything but sports data, reseeds the demo and signs everyone out.
          </span>
        </p>
        <Button
          size="sm"
          variant="outline"
          disabled={reset.isPending}
          onClick={() => setConfirming(true)}
        >
          <RotateCcw /> {reset.isPending ? "Resetting…" : "Reset data"}
        </Button>
      </div>
      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reset the demo world?</AlertDialogTitle>
            <AlertDialogDescription>
              Every account, table, bet, chat and settle-up goes back to the seeded demo. Games and
              player stats stay. Everyone, you included, is signed out.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => reset.mutate()}>Reset</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}

function SportsSections({ user, sports }: { user: User; sports: DevSports }) {
  const queryClient = useQueryClient();
  const act = useMutation({
    mutationFn: (run: () => Promise<void>) => run(),
    onError: (error) => toast.error(backendErrorMessage(error)),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: userKey(user.id, "dev-sports") });
      void queryClient.invalidateQueries({ queryKey: userKey(user.id, "ticker") });
    },
  });
  const { scenarios, chunks, speeds } = sports;

  return (
    <>
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
    </>
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

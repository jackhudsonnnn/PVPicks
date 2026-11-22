import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  Check,
  CircleCheck,
  CircleX,
  ClipboardCopy,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LoadingList } from "@/components/loading";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { LockoutRow } from "./lockout";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import { Chart, Stat } from "./charts";
import {
  PERF_WINDOWS,
  getAdminHealth,
  getPerf,
  getPerfLive,
  type PerfReport,
  type PerfSource,
  type PerfStats,
  type PerfWindow,
} from "./dashboard-api";
import { ago, bytes, count, duration, ms, percent, stamp } from "./format";

/** A route is flagged slow at this p95, and failing at this share of 5xx. */
const SLOW_P95_MS = 1000;
const FAILING_RATE = 0.01;

const errorRate = (stats: PerfStats) =>
  stats.requests === 0 ? 0 : stats.serverErrors / stats.requests;

/**
 * Performance: live runtime gauges and dependency health on top, then request volume, latency percentiles and
 * errors over a window from the stored minutes, and every route as a sortable table. Clicking a route narrows
 * the charts to it. Server numbers time the backend alone; Browsers is what players' browsers measured (the
 * network included) plus page loads. Everything a screenshot needs is on screen: window, source, route, range,
 * and when it was read.
 */
export function PerfTab({
  user,
  window,
  source,
  route,
  onChange,
}: {
  user: User;
  window: PerfWindow;
  source: PerfSource;
  route: string | null;
  onChange: (next: { window: PerfWindow; source: PerfSource; route: string | null }) => void;
}) {
  const selected = route
    ? { method: route.slice(0, route.indexOf(" ")), route: route.slice(route.indexOf(" ") + 1) }
    : null;
  const report = useQuery({
    queryKey: userKey(user.id, "admin-perf", window, source, route ?? ""),
    queryFn: ({ signal }) => getPerf({ window, source, route: selected }, signal),
    refetchInterval: 30_000,
    retry: false,
  });
  // The route table always lists every route, so drilling in keeps the table.
  const all = useQuery({
    queryKey: userKey(user.id, "admin-perf", window, source, ""),
    queryFn: ({ signal }) => getPerf({ window, source, route: null }, signal),
    refetchInterval: 30_000,
    retry: false,
    enabled: route !== null,
  });
  const routes = (route ? all.data : report.data)?.routes ?? [];

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <SegmentedTabs
          size="sm"
          ariaLabel="Source"
          className="w-full sm:w-64"
          tabs={[
            { id: "server", label: "Server" },
            { id: "client", label: "Browsers" },
          ]}
          value={source}
          onChange={(next) => onChange({ window, source: next, route: null })}
        />
        <SegmentedTabs
          size="sm"
          ariaLabel="Window"
          className="w-full sm:w-72"
          tabs={PERF_WINDOWS.map((id) => ({ id, label: id }))}
          value={window}
          onChange={(next) => onChange({ window: next, source, route })}
        />
      </div>

      <LivePanel user={user} />

      {route && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/40 bg-primary/5 px-3 py-2 text-sm">
          <span className="text-muted-foreground">Route</span>
          <code className="font-mono font-semibold">{route}</code>
          <Button
            size="sm"
            variant="ghost"
            className="ml-auto"
            onClick={() => onChange({ window, source, route: null })}
          >
            <X /> All routes
          </Button>
        </div>
      )}

      {report.isPending ? (
        <LoadingList label="Loading performance" rows={4} rowClassName="h-32" />
      ) : report.isError ? (
        <p role="alert">{backendErrorMessage(report.error)}</p>
      ) : (
        <>
          <WindowSummary report={report.data} />
          <WindowCharts report={report.data} />
        </>
      )}

      <RouteTable
        routes={routes}
        selected={route}
        onSelect={(next) => onChange({ window, source, route: next })}
      />
    </div>
  );
}

function LivePanel({ user }: { user: User }) {
  const live = useQuery({
    queryKey: userKey(user.id, "admin-perf-live"),
    queryFn: ({ signal }) => getPerfLive(signal),
    refetchInterval: 10_000,
    retry: false,
  });
  const health = useQuery({
    queryKey: userKey(user.id, "admin-health"),
    queryFn: ({ signal }) => getAdminHealth(signal),
    refetchInterval: 10_000,
    retry: false,
  });
  return (
    <section
      aria-labelledby="live-heading"
      className="space-y-2 rounded-xl border bg-surface/60 p-4"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3
          id="live-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Live · refreshes every 10 s
        </h3>
        {live.data && (
          <p className="font-mono text-[11px] text-muted-foreground">
            {live.data.host ?? "server"} · {live.data.version ?? "dev build"} · Java{" "}
            {live.data.jvm.javaVersion} · read {stamp(live.data.checkedAt)}
          </p>
        )}
      </div>
      {health.data && (
        <div className="flex flex-wrap gap-2">
          <Dependency name="PostgreSQL" status={health.data.postgres} />
          <Dependency name="Redis" status={health.data.redis} />
          <Dependency name="Kafka" status="NOT_CHECKED" />
          {live.data?.feeds.map((feed) => (
            <Dependency
              key={feed.league}
              name={`${feed.league} feed`}
              status={feedStatus(feed.lastSuccessAt, feed.lastFailureAt)}
              detail={
                feed.lastFailureAt &&
                (!feed.lastSuccessAt || feed.lastFailureAt > feed.lastSuccessAt)
                  ? `failing since ${ago(feed.lastFailureAt)}: ${feed.lastFailure ?? "unknown"}`
                  : `updated ${ago(feed.lastSuccessAt)}`
              }
            />
          ))}
        </div>
      )}
      {health.isError && <p role="alert">{backendErrorMessage(health.error)}</p>}
      <LockoutRow user={user} />
      {live.isPending ? (
        <LoadingList label="Loading live gauges" rows={1} rowClassName="h-20" />
      ) : live.isError ? (
        <p role="alert">{backendErrorMessage(live.error)}</p>
      ) : (
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Stat
            label="Uptime"
            value={duration(live.data.uptimeSeconds)}
            hint={`since ${stamp(live.data.startedAt)}`}
          />
          <Stat
            label="DB pool"
            value={`${live.data.database.active ?? "–"}/${live.data.database.max ?? "–"}`}
            tone={(live.data.database.waiting ?? 0) > 0 ? "bad" : undefined}
            hint={`${live.data.database.idle ?? "–"} idle · ${live.data.database.waiting ?? "–"} waiting`}
          />
          <Stat
            label="Heap"
            value={bytes(live.data.jvm.heapUsedBytes)}
            tone={
              live.data.jvm.heapUsedBytes / live.data.jvm.heapMaxBytes > 0.85 ? "warn" : undefined
            }
            hint={`of ${bytes(live.data.jvm.heapMaxBytes)}`}
          />
          <Stat
            label="CPU"
            value={percent(live.data.jvm.processCpu)}
            tone={(live.data.jvm.processCpu ?? 0) > 0.8 ? "warn" : undefined}
            hint={`${live.data.jvm.processors} cores · ${live.data.jvm.threads} threads`}
          />
          <Stat
            label="Outbox backlog"
            value={count(live.data.outbox.pending)}
            tone={(live.data.outbox.oldestPendingSeconds ?? 0) > 60 ? "bad" : undefined}
            hint={
              live.data.outbox.oldestPendingSeconds === null
                ? "nothing waiting"
                : `oldest ${duration(live.data.outbox.oldestPendingSeconds)}`
            }
          />
          <Stat label="Threads" value={count(live.data.jvm.threads)} hint="live JVM threads" />
        </dl>
      )}
    </section>
  );
}

function feedStatus(success: string | null, failure: string | null) {
  if (!success && !failure) return "UNKNOWN";
  if (failure && (!success || failure > success)) return "DOWN";
  // A league that hasn't polled for 15 minutes is stale.
  return success && Date.now() - new Date(success).getTime() > 15 * 60_000 ? "STALE" : "UP";
}

function Dependency({
  name,
  status,
  detail,
}: {
  name: string;
  status: string;
  detail?: string | null;
}) {
  const up = status === "UP";
  const down = status === "DOWN" || status === "OUT_OF_SERVICE";
  const Icon = up ? CircleCheck : down ? CircleX : AlertTriangle;
  const label =
    status === "UP"
      ? "Up"
      : status === "NOT_CHECKED"
        ? "Not checked"
        : status === "STALE"
          ? "Stale"
          : status === "DOWN"
            ? "Down"
            : status;
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs"
      title={detail ?? undefined}
    >
      <Icon
        className="size-3.5"
        style={{
          color: up ? "var(--viz-good)" : down ? "var(--viz-critical)" : "var(--viz-warning)",
        }}
      />
      <span className="font-semibold">{name}</span>
      <span className="text-muted-foreground">{label}</span>
    </span>
  );
}

function WindowSummary({ report }: { report: PerfReport }) {
  const totals = report.totals;
  const minutes = Math.max(
    1,
    (new Date(report.to).getTime() - new Date(report.from).getTime()) / 60_000,
  );
  const rate = errorRate(totals);
  return (
    <section className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="font-mono text-[11px] text-muted-foreground">
          {report.source === "server" ? "Server" : "Browsers"} · {report.window} ·{" "}
          {stamp(report.from)} → {stamp(report.to)} · {report.stepSeconds / 60} min per point
        </p>
        <CopySummary report={report} />
      </div>
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-8">
        <Stat
          label="Requests"
          value={count(totals.requests)}
          hint={`${(totals.requests / minutes).toFixed(1)} / min`}
        />
        <Stat
          label={report.source === "server" ? "5xx" : "5xx / failed"}
          value={count(totals.serverErrors)}
          tone={rate > FAILING_RATE ? "bad" : undefined}
          hint={percent(rate, 2)}
        />
        <Stat
          label="4xx"
          value={count(totals.clientErrors)}
          hint={percent(totals.requests ? totals.clientErrors / totals.requests : 0, 2)}
        />
        <Stat
          label="429"
          value={count(totals.rateLimited)}
          tone={totals.rateLimited > 0 ? "warn" : undefined}
          hint="rate limited"
        />
        <Stat label="p50" value={ms(totals.p50)} />
        <Stat
          label="p95"
          value={ms(totals.p95)}
          tone={(totals.p95 ?? 0) > SLOW_P95_MS ? "warn" : undefined}
        />
        <Stat label="p99" value={ms(totals.p99)} />
        <Stat label="Max" value={ms(totals.maxMs)} hint={`avg ${ms(totals.avgMs)}`} />
      </dl>
    </section>
  );
}

/** Copies a plain-text summary to paste next to a screenshot. */
function CopySummary({ report }: { report: PerfReport }) {
  const [copied, setCopied] = useState(false);
  const text = () => {
    const t = report.totals;
    const slowest = [...report.routes]
      .filter((r) => r.stats.p95 !== null)
      .sort((a, b) => (b.stats.p95 ?? 0) - (a.stats.p95 ?? 0))
      .slice(0, 5)
      .map(
        (r) =>
          `  ${r.method} ${r.route}: p95 ${ms(r.stats.p95)}, p99 ${ms(r.stats.p99)}, ${count(r.stats.requests)} req, ${r.stats.serverErrors} 5xx`,
      );
    return [
      `P2Picks perf · ${report.source} · ${report.window}${report.route ? ` · ${report.method} ${report.route}` : ""}`,
      `${stamp(report.from)} → ${stamp(report.to)}`,
      `requests ${count(t.requests)} · 5xx ${t.serverErrors} · 4xx ${t.clientErrors} · 429 ${t.rateLimited}`,
      `p50 ${ms(t.p50)} · p95 ${ms(t.p95)} · p99 ${ms(t.p99)} · max ${ms(t.maxMs)}`,
      ...(slowest.length ? ["slowest routes by p95:", ...slowest] : []),
      `copied ${stamp(Date.now())} · ${window.location.href}`,
    ].join("\n");
  };
  return (
    <Button
      size="sm"
      variant="outline"
      onClick={() =>
        void navigator.clipboard?.writeText(text()).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        })
      }
    >
      {copied ? <Check /> : <ClipboardCopy />} {copied ? "Copied" : "Copy summary"}
    </Button>
  );
}

function axisLabel(iso: string, stepSeconds: number) {
  const date = new Date(iso);
  return stepSeconds >= 3600
    ? date.toLocaleString(undefined, { weekday: "short", hour: "2-digit" })
    : date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

function WindowCharts({ report }: { report: PerfReport }) {
  const labels = report.series.map((point) => axisLabel(point.at, report.stepSeconds));
  const series = report.series.map((point) => point.stats);
  return (
    <div className="grid gap-6 xl:grid-cols-3">
      <ChartPanel title={`Requests per ${report.stepSeconds / 60} min`}>
        <Chart
          kind="stack"
          title="Requests per interval"
          labels={labels}
          series={[
            {
              id: "requests",
              label: "Requests",
              color: "var(--viz-1)",
              values: series.map((s) => s.requests),
            },
          ]}
        />
      </ChartPanel>
      <ChartPanel title="Latency">
        <Chart
          title="Latency percentiles per interval"
          labels={labels}
          format={(value) => ms(value)}
          series={[
            { id: "p50", label: "p50", color: "var(--viz-1)", values: series.map((s) => s.p50) },
            { id: "p95", label: "p95", color: "var(--viz-2)", values: series.map((s) => s.p95) },
            { id: "p99", label: "p99", color: "var(--viz-3)", values: series.map((s) => s.p99) },
          ]}
        />
      </ChartPanel>
      <ChartPanel title="Errors">
        <Chart
          kind="stack"
          title="Errors per interval"
          labels={labels}
          series={[
            {
              id: "5xx",
              label: report.source === "server" ? "5xx" : "5xx / failed",
              color: "var(--viz-critical)",
              values: series.map((s) => s.serverErrors),
            },
            {
              id: "4xx",
              label: "4xx",
              color: "var(--viz-serious)",
              values: series.map((s) => s.clientErrors),
            },
            {
              id: "429",
              label: "429",
              color: "var(--viz-warning)",
              values: series.map((s) => s.rateLimited),
            },
          ]}
        />
      </ChartPanel>
    </div>
  );
}

function ChartPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="min-w-0 space-y-2 rounded-xl border bg-surface/60 p-4">
      <h3 className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground">
        {title}
      </h3>
      {children}
    </section>
  );
}

type SortKey = "requests" | "errors" | "p50" | "p95" | "p99" | "maxMs";
const COLUMNS: [SortKey, string][] = [
  ["requests", "Requests"],
  ["errors", "5xx %"],
  ["p50", "p50"],
  ["p95", "p95"],
  ["p99", "p99"],
  ["maxMs", "Max"],
];

function sortValue(stats: PerfStats, key: SortKey) {
  return key === "errors" ? errorRate(stats) : (stats[key] ?? -1);
}

function RouteTable({
  routes,
  selected,
  onSelect,
}: {
  routes: PerfReport["routes"];
  selected: string | null;
  onSelect: (route: string) => void;
}) {
  const [sort, setSort] = useState<{ key: SortKey; descending: boolean }>({
    key: "p95",
    descending: true,
  });
  const [filter, setFilter] = useState("");
  const rows = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return routes
      .filter((row) => !needle || `${row.method} ${row.route}`.toLowerCase().includes(needle))
      .sort((a, b) => {
        const difference = sortValue(a.stats, sort.key) - sortValue(b.stats, sort.key);
        return sort.descending ? -difference : difference;
      });
  }, [routes, filter, sort]);
  return (
    <section className="space-y-3" aria-labelledby="routes-heading">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3
          id="routes-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Routes · {routes.length}
        </h3>
        <Input
          aria-label="Filter routes"
          placeholder="Filter routes"
          className="h-8 w-56"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
      </div>
      <div className="overflow-x-auto rounded-lg border bg-surface">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="border-b text-left font-mono text-[10px] uppercase text-muted-foreground">
            <tr>
              <th className="px-3 py-2 font-normal">Route</th>
              {COLUMNS.map(([key, label]) => (
                <th key={key} className="px-2 py-2 text-right font-normal">
                  <button
                    type="button"
                    className="inline-flex items-center gap-0.5 uppercase hover:text-foreground"
                    onClick={() =>
                      setSort((current) => ({
                        key,
                        descending: current.key === key ? !current.descending : true,
                      }))
                    }
                    aria-sort={
                      sort.key === key ? (sort.descending ? "descending" : "ascending") : undefined
                    }
                  >
                    {label}
                    {sort.key === key &&
                      (sort.descending ? (
                        <ArrowDown className="size-3" />
                      ) : (
                        <ArrowUp className="size-3" />
                      ))}
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-4 text-center text-muted-foreground">
                  No requests in this window.
                </td>
              </tr>
            )}
            {rows.map((row) => {
              const id = `${row.method} ${row.route}`;
              const slow = (row.stats.p95 ?? 0) > SLOW_P95_MS;
              const failing = errorRate(row.stats) > FAILING_RATE;
              return (
                <tr
                  key={id}
                  className={cn(
                    "cursor-pointer hover:bg-muted/50",
                    selected === id && "bg-primary/10",
                  )}
                  onClick={() => onSelect(id)}
                >
                  <td className="px-3 py-1.5">
                    <button
                      type="button"
                      className="text-left font-mono text-xs"
                      onClick={() => onSelect(id)}
                    >
                      <span className="mr-2 inline-block w-12 font-bold">{row.method}</span>
                      {row.route}
                    </button>
                    {(slow || failing) && (
                      <span
                        className="ml-2 inline-flex items-center gap-1 text-[10px] font-bold uppercase"
                        style={{ color: failing ? "var(--viz-critical)" : "var(--viz-warning)" }}
                      >
                        <AlertTriangle className="size-3" /> {failing ? "failing" : "slow"}
                      </span>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">{count(row.stats.requests)}</td>
                  <td className={cn("px-2 py-1.5 text-right font-mono", failing && "text-loss")}>
                    {percent(errorRate(row.stats), 2)}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">{ms(row.stats.p50)}</td>
                  <td className={cn("px-2 py-1.5 text-right font-mono", slow && "font-bold")}>
                    {ms(row.stats.p95)}
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">{ms(row.stats.p99)}</td>
                  <td className="px-2 py-1.5 text-right font-mono">{ms(row.stats.maxMs)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted-foreground">
        Percentiles are estimated from latency buckets (5 ms to 10 s). Rows over {ms(SLOW_P95_MS)}{" "}
        p95 are marked slow, and over {percent(FAILING_RATE)} 5xx failing. “(no route)” is requests
        refused before reaching a handler, such as signed-out calls. Data is kept 7 days; the newest
        minute is still filling.
      </p>
    </section>
  );
}

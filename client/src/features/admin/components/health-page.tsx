import { EmptyState } from "@/components/empty-state";
import { newId } from "@/lib/ids";
import { timeLabel } from "@/lib/time";
import { useEffect, useMemo, useState } from "react";
import { CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Database,
  Gauge,
  Scale,
  XCircle,
} from "lucide-react";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { Button } from "@/components/ui/button";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { cn } from "@/lib/utils";
import { sampleWorlds } from "../admin-data";
import { storageUsage } from "@/lib/storage";
import { emitError, onError } from "@/features/notifications/error-bus";
import { zeroSumAudit } from "../analytics";
import { healthStatus, healthTick, percentile, type HealthPoint } from "../health";
import { Kpi, Panel } from "./admin-ui";

interface LoggedError {
  id: string;
  title: string;
  description?: string | undefined;
  at: number;
}

/** This app's saved data in the browser, in KB. */
const storageKb = () => Math.round(storageUsage("p2picks").reduce((n, x) => n + x.bytes, 0) / 1024);

/** Site health: simulated latency/errors, real in-app errors, and the ledger zero-sum check. */
export function HealthPage() {
  const [pts, setPts] = useState<HealthPoint[]>([]);
  const [errors, setErrors] = useState<LoggedError[]>([]);
  const [kb, setKb] = useState(0);
  const [loadMs, setLoadMs] = useState<number | null>(null);

  useEffect(() => {
    let p: HealthPoint[] = [];
    for (let i = 0; i < 20; i++) p = healthTick(p, Math.random);
    setPts(p);
    const t = setInterval(() => setPts((x) => healthTick(x, Math.random)), 2000);
    setKb(storageKb());
    const nav = performance.getEntriesByType("navigation")[0] as
      PerformanceNavigationTiming | undefined;
    if (nav) setLoadMs(Math.round(nav.loadEventEnd || nav.domContentLoadedEventEnd));
    const off = onError((e) =>
      setErrors((prev) =>
        [{ id: newId("err"), ...e, at: Date.now() }, ...prev].slice(0, 50),
      ),
    );
    const onWin = (e: ErrorEvent) => emitError("Page error", e.message);
    window.addEventListener("error", onWin);
    return () => {
      clearInterval(t);
      off();
      window.removeEventListener("error", onWin);
    };
  }, []);

  const audits = useMemo(
    () =>
      Object.values(sampleWorlds()).map((w) => ({
        id: w.id,
        label: w.label,
        ...zeroSumAudit(w.data.bets),
      })),
    [],
  );
  const status = healthStatus(pts);
  const p95 = percentile(
    pts.map((p) => p.p95),
    0.95,
  );
  const p50 = percentile(
    pts.map((p) => p.p50),
    0.5,
  );
  const errRate = pts.length
    ? Math.round((pts.reduce((s, p) => s + p.errors, 0) / pts.length) * 100)
    : 0;
  const broken = audits.reduce((s, a) => s + a.broken.length, 0);

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8 md:py-10">
      <div>
        <p className="font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
          Monitor
        </p>
        <h1 className="mt-1 text-4xl font-black uppercase leading-[0.9] md:text-5xl">
          Site health
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Latency and server errors are simulated until the app has a server. In-app errors and the
          ledger check are real.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi
          icon={status === "healthy" ? CheckCircle2 : AlertTriangle}
          label="Status"
          value={status === "healthy" ? "Healthy" : status === "degraded" ? "Degraded" : "Down"}
          tone={status === "healthy" ? "win" : "loss"}
        />
        <Kpi icon={Gauge} label="Latency p50 / p95" value={`${p50} / ${p95}ms`} />
        <Kpi
          icon={Activity}
          label="Error rate"
          value={`${errRate}%`}
          tone={errRate > 10 ? "loss" : undefined}
        />
        <Kpi
          icon={Database}
          label="Saved on device"
          value={`${kb} KB`}
          hint={loadMs ? `Page loaded in ${loadMs}ms` : "Local data"}
        />
      </div>

      <Panel title="Response time" hint="Every 2 seconds.">
        <ChartContainer
          config={{
            p50: { label: "p50", color: "var(--primary)" },
            p95: { label: "p95", color: "var(--chart-4)" },
          }}
          className="aspect-auto h-64 w-full"
        >
          <LineChart data={pts} margin={{ left: 0, right: 8, top: 8 }}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="t" tickLine={false} axisLine={false} tickFormatter={() => ""} />
            <YAxis tickLine={false} axisLine={false} width={44} unit="ms" />
            <ChartTooltip content={<ChartTooltipContent hideLabel />} />
            <ChartLegend content={<ChartLegendContent />} />
            <Line
              dataKey="p50"
              type="monotone"
              stroke="var(--color-p50)"
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
            <Line
              dataKey="p95"
              type="monotone"
              stroke="var(--color-p95)"
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ChartContainer>
      </Panel>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel title="Ledger check" hint="Every resolved points ticket must add up to zero.">
          <ul className="space-y-2 text-sm">
            {audits.map((a) => (
              <li key={a.id} className="flex items-center gap-2">
                {a.broken.length ? (
                  <XCircle className="size-4 text-loss" />
                ) : (
                  <Scale className="size-4 text-win" />
                )}
                <span className="flex-1 font-semibold">{a.label}</span>
                <span
                  className={cn(
                    "font-mono text-xs",
                    a.broken.length ? "text-loss" : "text-muted-foreground",
                  )}
                >
                  {a.broken.length
                    ? `${a.broken.length} off of ${a.checked}`
                    : `${a.checked} balanced`}
                </span>
              </li>
            ))}
          </ul>
          <p className={cn("mt-3 text-xs", broken ? "text-loss" : "text-muted-foreground")}>
            {broken
              ? `Tickets off: ${audits.flatMap((a) => a.broken).join(", ")}`
              : "No tickets off."}
          </p>
        </Panel>
        <Panel title="Error log" hint="Errors raised in the app while this page is open.">
          <div className="mb-3 flex gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => emitError("Test error", "Sent from Site health.")}
            >
              Send test error
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => setErrors([])}
              disabled={!errors.length}
            >
              Clear
            </Button>
          </div>
          {errors.length ? (
            <ScrollContainer isVisible={false} className="max-h-60">
              <AnimatedList>
                {errors.map((e) => (
                  <AnimatedItem
                    key={e.id}
                    className="mb-1.5 rounded-lg bg-loss/10 px-3 py-2 text-sm"
                  >
                    <p className="font-semibold text-loss">{e.title}</p>
                    {e.description && (
                      <p className="text-xs text-muted-foreground">{e.description}</p>
                    )}
                    <p className="font-mono text-[10px] text-muted-foreground">
                      {timeLabel(e.at)}
                    </p>
                  </AnimatedItem>
                ))}
              </AnimatedList>
            </ScrollContainer>
          ) : (
            <EmptyState>No errors.</EmptyState>
          )}
        </Panel>
      </div>
    </div>
  );
}

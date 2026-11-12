import { useEffect, useMemo, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  XAxis,
  YAxis,
} from "recharts";
import { Activity, CheckCircle2, Flag, Scale, Ticket, Users, XCircle } from "lucide-react";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Donut, Kpi, Panel } from "./admin-ui";
import { sampleWorlds, type AdminWorld } from "../admin-data";
import { formatPts } from "@/features/bets/ledger";
import { PLAYER_ACCOUNTS, type PlayerAccountId } from "@/features/auth/demo-accounts";
import { useReports } from "@/features/social/reports-storage";
import { REPORT_REASONS } from "@/features/social/safety";
import {
  busiestTables,
  liveTick,
  modeBreakdown,
  outcomes,
  pointsMoved,
  statusBreakdown,
  ticketsByDay,
  zeroSumAudit,
  type LivePoint,
} from "../analytics";

type Cohort = "all" | PlayerAccountId;

const label = (id: PlayerAccountId) => sampleWorlds()[id].label;
const PALETTE = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
  "var(--primary)",
  "var(--loss)",
  "var(--foreground)",
  "var(--muted-foreground)",
];

const PLAYER_CONFIG = {
  newbie: { label: "Newbie", color: "var(--chart-2)" },
  intermediate: { label: "Intermediate", color: "var(--chart-4)" },
  pro: { label: "Pro", color: "var(--primary)" },
} satisfies ChartConfig;

const STATUS_CONFIG = {
  active: { label: "Active", color: "var(--primary)" },
  pending: { label: "Pending", color: "var(--chart-4)" },
  resolved: { label: "Resolved", color: "var(--chart-2)" },
  washed: { label: "Washed", color: "var(--muted-foreground)" },
} satisfies ChartConfig;

const OUTCOME_CONFIG = {
  win: { label: "Win", color: "var(--win)" },
  loss: { label: "Loss", color: "var(--loss)" },
  wash: { label: "Wash", color: "var(--chart-4)" },
  pass: { label: "No guess", color: "var(--muted-foreground)" },
} satisfies ChartConfig;

/** Live dashboards over the three sample players, plus the reports queue. */
export function AdminDashboard() {
  // The three sample worlds, built once (Pro is the big one).
  const worlds = useMemo(() => {
    const all = sampleWorlds();
    return Object.fromEntries(
      PLAYER_ACCOUNTS.map((id) => [id, { ...all[id].data, playerId: all[id].playerId }]),
    ) as Record<PlayerAccountId, AdminWorld["data"] & { playerId: string }>;
  }, []);
  const [cohort, setCohort] = useState<Cohort>("all");
  const [live, setLive] = useState<LivePoint[]>([]);
  const { reports } = useReports();

  useEffect(() => {
    let pts: LivePoint[] = [];
    for (let i = 0; i < 20; i++) pts = liveTick(pts, Math.random);
    setLive(pts);
    const t = setInterval(() => setLive((p) => liveTick(p, Math.random)), 2000);
    return () => clearInterval(t);
  }, []);

  const picked = cohort === "all" ? PLAYER_ACCOUNTS : [cohort];
  const bets = picked.flatMap((id) => worlds[id].bets);
  const tables = picked.flatMap((id) =>
    worlds[id].tables.filter((t) => t.memberIds.includes(worlds[id].playerId)),
  );
  const messages = picked.flatMap((id) => worlds[id].messages);
  const audit = zeroSumAudit(bets);
  const modes = modeBreakdown(bets);
  const modeConfig = Object.fromEntries(
    modes.map((m, i) => [m.id, { label: m.name, color: PALETTE[i % PALETTE.length]! }]),
  ) satisfies ChartConfig;
  const statuses = statusBreakdown(bets).filter((s) => s.count > 0);
  const byDay = ticketsByDay(bets);
  // Each sample world has its own copy of the shared tables, so label them by player.
  const busiest = picked
    .flatMap((id) =>
      busiestTables(
        worlds[id].tables.filter((t) => t.memberIds.includes(worlds[id].playerId)),
        worlds[id].messages,
      ).map((t) => ({ ...t, name: cohort === "all" ? `${t.name} · ${label(id)}` : t.name })),
    )
    .sort((a, b) => b.messages - a.messages)
    .slice(0, 6);
  const outcomeRows = PLAYER_ACCOUNTS.map((id) => ({
    player: label(id),
    ...outcomes(worlds[id].playerId, worlds[id].bets),
  }));
  const openReports = reports.filter((r) => r.status === "open" || r.status === "reviewing").length;
  const reasonData = REPORT_REASONS.map((r) => ({
    id: r.id,
    name: r.label,
    count: reports.filter((x) => x.reason === r.id).length,
  })).filter((r) => r.count > 0);
  const reasonConfig = Object.fromEntries(
    REPORT_REASONS.map((r, i) => [r.id, { label: r.label, color: PALETTE[i % PALETTE.length]! }]),
  ) satisfies ChartConfig;
  const lastMin = live.slice(-30).reduce((s, p) => s + picked.reduce((x, id) => x + p[id], 0), 0);
  const activeNow = bets.filter((b) => b.status === "active").length;

  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-6 md:px-8 md:py-10">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="flex items-center gap-2 font-mono text-[11px] font-bold uppercase tracking-widest text-primary">
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-75" />
              <span className="relative inline-flex size-2 rounded-full bg-primary" />
            </span>
            Live
          </p>
          <h1 className="mt-1 text-4xl font-black uppercase leading-[0.9] md:text-5xl">
            Dashboard
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Sample data from the Newbie, Intermediate and Pro accounts. Live activity is simulated.
          </p>
        </div>
        <SegmentedTabs<Cohort>
          size="sm"
          ariaLabel="Which player"
          className="w-full sm:w-auto sm:min-w-80"
          tabs={[
            { id: "all", label: "All" },
            ...PLAYER_ACCOUNTS.map((id) => ({ id, label: label(id) })),
          ]}
          value={cohort}
          onChange={setCohort}
        />
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Kpi icon={Users} label="Tables" value={String(tables.length)} />
        <Kpi
          icon={Ticket}
          label="Tickets"
          value={bets.length.toLocaleString()}
          hint={`${activeNow} live now`}
        />
        <Kpi
          icon={Activity}
          label="Actions, last minute"
          value={lastMin.toLocaleString()}
          tone="primary"
        />
        <Kpi icon={Scale} label="Points moved" value={`$${formatPts(pointsMoved(bets))}`} />
        <Kpi
          icon={audit.broken.length ? XCircle : CheckCircle2}
          label="Zero-sum check"
          value={audit.broken.length ? `${audit.broken.length} off` : "Balanced"}
          hint={`${audit.checked.toLocaleString()} resolved tickets checked`}
          tone={audit.broken.length ? "loss" : "win"}
        />
        <Kpi
          icon={Flag}
          label="Open reports"
          value={String(openReports)}
          tone={openReports ? "loss" : undefined}
        />
        <Kpi icon={Users} label="Chat lines" value={messages.length.toLocaleString()} />
        <Kpi
          icon={Ticket}
          label="Wash rate"
          value={`${bets.length ? Math.round((bets.filter((b) => b.status === "washed").length / bets.length) * 100) : 0}%`}
        />
      </div>

      <Panel title="Live activity" hint="Picks, chats and reactions every 2 seconds, per player.">
        <ChartContainer config={PLAYER_CONFIG} className="aspect-auto h-64 w-full">
          <LineChart data={live} margin={{ left: 0, right: 8, top: 8 }}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="t" tickLine={false} axisLine={false} tickFormatter={() => ""} />
            <YAxis tickLine={false} axisLine={false} width={44} allowDecimals={false} />
            <ChartTooltip content={<ChartTooltipContent hideLabel />} />
            <ChartLegend content={<ChartLegendContent />} />
            {picked.map((id) => (
              <Line
                key={id}
                dataKey={id}
                type="monotone"
                stroke={`var(--color-${id})`}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            ))}
          </LineChart>
        </ChartContainer>
      </Panel>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel title="Tickets by mode">
          <Donut
            config={modeConfig}
            data={modes.map((m) => ({ key: m.id, name: m.name, value: m.count }))}
          />
        </Panel>
        <Panel title="Tickets by status">
          <Donut
            config={STATUS_CONFIG}
            data={statuses.map((s) => ({
              key: s.status,
              name: STATUS_CONFIG[s.status].label,
              value: s.count,
            }))}
          />
        </Panel>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel title="Tickets per day">
          <ChartContainer
            config={{ count: { label: "Tickets", color: "var(--primary)" } }}
            className="aspect-auto h-60 w-full"
          >
            <AreaChart data={byDay} margin={{ left: 0, right: 8, top: 8 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="day" tickLine={false} axisLine={false} minTickGap={16} />
              <YAxis tickLine={false} axisLine={false} width={44} allowDecimals={false} />
              <ChartTooltip content={<ChartTooltipContent />} />
              <Area
                dataKey="count"
                type="monotone"
                fill="var(--color-count)"
                fillOpacity={0.25}
                stroke="var(--color-count)"
                strokeWidth={2}
              />
            </AreaChart>
          </ChartContainer>
        </Panel>
        <Panel
          title="How each player's tickets went"
          hint="Each sample world's own player (newbie_nate, inter_ivy, pro_pat)."
        >
          <ChartContainer config={OUTCOME_CONFIG} className="aspect-auto h-60 w-full">
            <BarChart data={outcomeRows} margin={{ left: 0, right: 8, top: 8 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="player" tickLine={false} axisLine={false} />
              <YAxis tickLine={false} axisLine={false} width={44} allowDecimals={false} />
              <ChartTooltip content={<ChartTooltipContent />} />
              <ChartLegend content={<ChartLegendContent />} />
              {(["win", "loss", "wash", "pass"] as const).map((k, i, all) => (
                <Bar
                  key={k}
                  dataKey={k}
                  stackId="o"
                  fill={`var(--color-${k})`}
                  radius={i === all.length - 1 ? [4, 4, 0, 0] : 0}
                />
              ))}
            </BarChart>
          </ChartContainer>
        </Panel>
      </div>

      <div className="grid gap-4 md:grid-cols-[3fr_2fr]">
        <Panel title="Busiest tables" hint="By chat lines.">
          <ChartContainer
            config={{ messages: { label: "Chat lines", color: "var(--chart-2)" } }}
            className="aspect-auto h-64 w-full"
          >
            <BarChart data={busiest} layout="vertical" margin={{ left: 0, right: 16 }}>
              <XAxis type="number" hide />
              <YAxis type="category" dataKey="name" tickLine={false} axisLine={false} width={170} />
              <ChartTooltip content={<ChartTooltipContent />} />
              <Bar dataKey="messages" fill="var(--color-messages)" radius={4} />
            </BarChart>
          </ChartContainer>
        </Panel>
        <Panel title="Reports by reason">
          {reasonData.length ? (
            <Donut
              config={reasonConfig}
              data={reasonData.map((r) => ({ key: r.id, name: r.name, value: r.count }))}
            />
          ) : (
            <p className="py-10 text-center text-sm text-muted-foreground">No reports.</p>
          )}
        </Panel>
      </div>
    </div>
  );
}

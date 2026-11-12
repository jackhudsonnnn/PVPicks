import { Cell, Pie, PieChart } from "recharts";
import { Users } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";

export function Donut({
  config,
  data,
}: {
  config: ChartConfig;
  data: { key: string; name: string; value: number }[];
}) {
  const total = data.reduce((s, d) => s + d.value, 0);
  return (
    <div className="relative">
      <ChartContainer config={config} className="mx-auto aspect-square max-h-64 w-full">
        <PieChart>
          <ChartTooltip content={<ChartTooltipContent nameKey="key" hideLabel />} />
          <Pie
            data={data}
            dataKey="value"
            nameKey="key"
            innerRadius="58%"
            outerRadius="85%"
            strokeWidth={2}
            paddingAngle={2}
          >
            {data.map((d) => (
              <Cell key={d.key} fill={`var(--color-${d.key})`} />
            ))}
          </Pie>
          <ChartLegend
            content={<ChartLegendContent nameKey="key" />}
            className="flex-wrap gap-y-1"
          />
        </PieChart>
      </ChartContainer>
      <div className="pointer-events-none absolute inset-x-0 top-[38%] text-center">
        <p className="scoreboard text-3xl leading-none">{total.toLocaleString()}</p>
        <p className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
          total
        </p>
      </div>
    </div>
  );
}

export function Panel({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="min-w-0 rounded-2xl border bg-surface p-4 sm:p-5">
      <h2 className="font-display text-2xl font-extrabold uppercase leading-none">{title}</h2>
      {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
      <div className="mt-3">{children}</div>
    </section>
  );
}

export function Kpi({
  icon: Icon,
  label,
  value,
  hint,
  tone,
}: {
  icon: typeof Users;
  label: string;
  value: string;
  hint?: string;
  tone?: "win" | "loss" | "primary" | undefined;
}) {
  return (
    <div className="min-w-0 rounded-xl border bg-surface p-4">
      <p className="flex items-center gap-1.5 font-mono text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
        <Icon className="size-3.5 shrink-0" /> {label}
      </p>
      <p
        className={cn(
          "scoreboard mt-1 break-words text-3xl leading-none",
          tone === "win" && "text-win",
          tone === "loss" && "text-loss",
          tone === "primary" && "text-primary",
        )}
      >
        {value}
      </p>
      {hint && <p className="mt-1 text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function Badge({
  children,
  tone,
}: {
  children: React.ReactNode;
  tone?: "win" | "loss" | "info";
}) {
  return (
    <span
      className={cn(
        "rounded-full px-2 py-0.5 font-mono text-[10px] font-bold uppercase tracking-wider",
        tone === "win" && "bg-primary/15 text-primary",
        tone === "loss" && "bg-loss/15 text-loss",
        tone === "info" && "bg-chart-2/15 text-chart-2",
        !tone && "bg-surface-raised text-muted-foreground",
      )}
    >
      {children}
    </span>
  );
}

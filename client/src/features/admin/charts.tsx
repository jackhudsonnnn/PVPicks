import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

/**
 * Small SVG charts for the admin dashboard, drawn to the dataviz rules: one y-axis, thin 2px lines, 4px rounded
 * bar ends on the baseline with 2px gaps, a recessive grid, a legend for two or more series plus direct labels at
 * the line ends, and a hover crosshair with a tooltip of every series at that point. Colors come from the
 * validated `--viz-*` tokens, so the dark and light themes each use their own steps.
 */

export interface Series {
  id: string;
  label: string;
  /** A CSS color, normally `var(--viz-1)` and on. */
  color: string;
  values: (number | null)[];
}

interface ChartProps {
  /** One label per point (the x axis), e.g. formatted times. */
  labels: string[];
  series: Series[];
  /** "line" draws each series as a line; "stack" stacks them as bars. */
  kind?: "line" | "stack";
  height?: number;
  format?: (value: number) => string;
  /** What the chart shows, for screen readers and the empty state. */
  title: string;
  className?: string;
  /** Labels for the x axis at most this many, evenly spaced. */
  ticks?: number;
}

const PAD = { top: 12, right: 56, bottom: 24, left: 60 };

function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useLayoutEffect(() => {
    const node = ref.current;
    if (!node) return;
    setWidth(node.clientWidth || 640);
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setWidth(Math.max(200, entry.contentRect.width));
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

/** A "nice" axis top and step for a maximum, so ticks land on round numbers. Counts and milliseconds are whole,
 *  so the step is never below 1. */
function niceScale(max: number, count = 4) {
  if (max <= 0) return { top: 1, step: 1 };
  const raw = Math.max(1, max / count);
  const power = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * power).find((s) => s >= raw) ?? raw;
  return { top: step * Math.ceil(max / step), step };
}

export function Chart({
  labels,
  series,
  kind = "line",
  height = 180,
  format = (value) => String(Math.round(value)),
  title,
  className,
  ticks = 6,
}: ChartProps) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const count = labels.length;
  const plotWidth = Math.max(10, width - PAD.left - PAD.right);
  const plotHeight = height - PAD.top - PAD.bottom;
  const max = useMemo(() => {
    let top = 0;
    for (let i = 0; i < count; i++) {
      if (kind === "stack") {
        top = Math.max(
          top,
          series.reduce((sum, s) => sum + (s.values[i] ?? 0), 0),
        );
      } else {
        for (const s of series) top = Math.max(top, s.values[i] ?? 0);
      }
    }
    return top;
  }, [count, kind, series]);
  const scale = niceScale(max);
  const slot = count > 0 ? plotWidth / count : plotWidth;
  const x = (i: number) =>
    PAD.left +
    (kind === "stack"
      ? slot * i + slot / 2
      : count <= 1
        ? plotWidth / 2
        : (plotWidth * i) / (count - 1));
  const y = (value: number) => PAD.top + plotHeight - (value / scale.top) * plotHeight;
  const empty = max === 0;

  // Never more labels than fit: about 72px each.
  const fit = Math.max(2, Math.min(ticks, Math.floor(plotWidth / 72)));
  const tickEvery = Math.max(1, Math.ceil(count / fit));
  const endLabels = endLabelPositions(series, y);
  const gridLines: number[] = [];
  for (let v = 0; v <= scale.top + 1e-9; v += scale.step) gridLines.push(v);

  function pick(event: React.PointerEvent<SVGRectElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    const offset = event.clientX - box.left;
    const index =
      kind === "stack"
        ? Math.floor(offset / slot)
        : Math.round((offset / Math.max(1, plotWidth)) * Math.max(0, count - 1));
    setHover(Math.min(count - 1, Math.max(0, index)));
  }

  const legend = series.length >= 2;
  const hovered = hover === null ? null : hover;

  return (
    <figure className={cn("relative min-w-0", className)}>
      {legend && (
        <figcaption className="mb-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          {series.map((s) => (
            <span key={s.id} className="inline-flex items-center gap-1.5">
              <span
                aria-hidden
                className={cn(
                  "inline-block",
                  kind === "stack" ? "size-2.5 rounded-sm" : "h-0.5 w-3",
                )}
                style={{ background: s.color }}
              />
              {s.label}
            </span>
          ))}
        </figcaption>
      )}
      <div ref={ref} className="relative">
        <svg
          width={width}
          height={height}
          role="img"
          aria-label={title}
          className="block max-w-full select-none"
        >
          {gridLines.map((v) => (
            <g key={v}>
              <line
                x1={PAD.left}
                x2={PAD.left + plotWidth}
                y1={y(v)}
                y2={y(v)}
                stroke="var(--viz-grid)"
              />
              <text
                x={PAD.left - 6}
                y={y(v)}
                textAnchor="end"
                dominantBaseline="middle"
                className="fill-muted-foreground font-mono text-[10px]"
              >
                {format(v)}
              </text>
            </g>
          ))}
          {labels.map((label, i) =>
            i % tickEvery === 0 ? (
              <text
                key={i}
                x={x(i)}
                y={height - 6}
                textAnchor="middle"
                className="fill-muted-foreground font-mono text-[10px]"
              >
                {label}
              </text>
            ) : null,
          )}
          {!empty &&
            kind === "stack" &&
            labels.map((_, i) => {
              let base = 0;
              const barWidth = Math.max(1, Math.min(24, slot - 2));
              return (
                <g key={i}>
                  {series.map((s, si) => {
                    const value = s.values[i] ?? 0;
                    if (value <= 0) return null;
                    const top = y(base + value);
                    const bottom = y(base);
                    base += value;
                    const isTop = series.slice(si + 1).every((next) => (next.values[i] ?? 0) <= 0);
                    // A 2px surface gap between stacked segments.
                    const h = Math.max(1, bottom - top - (si > 0 ? 2 : 0));
                    return (
                      <rect
                        key={s.id}
                        x={x(i) - barWidth / 2}
                        y={top}
                        width={barWidth}
                        height={h}
                        rx={isTop ? Math.min(4, barWidth / 2) : 0}
                        fill={s.color}
                        opacity={hovered === null || hovered === i ? 1 : 0.55}
                      />
                    );
                  })}
                </g>
              );
            })}
          {!empty &&
            kind === "line" &&
            series.map((s) => {
              let d = "";
              let pen = false;
              s.values.forEach((value, i) => {
                if (value === null) {
                  pen = false;
                  return;
                }
                d += `${pen ? "L" : "M"}${x(i).toFixed(1)},${y(value).toFixed(1)}`;
                pen = true;
              });
              let lastIndex = -1;
              s.values.forEach((value, i) => {
                if (value !== null) lastIndex = i;
              });
              const last = lastIndex >= 0 ? s.values[lastIndex] : null;
              return (
                <g key={s.id}>
                  <path d={d} fill="none" stroke={s.color} strokeWidth={2} strokeLinejoin="round" />
                  {last !== null && last !== undefined && series.length <= 4 && (
                    <text
                      x={x(lastIndex) + 6}
                      y={endLabels.get(s.id) ?? y(last)}
                      dominantBaseline="middle"
                      className="fill-foreground font-mono text-[10px]"
                    >
                      {series.length > 1 ? s.label : format(last)}
                    </text>
                  )}
                </g>
              );
            })}
          {hovered !== null && !empty && (
            <g pointerEvents="none">
              <line
                x1={x(hovered)}
                x2={x(hovered)}
                y1={PAD.top}
                y2={PAD.top + plotHeight}
                stroke="currentColor"
                className="text-muted-foreground"
                strokeDasharray="3 3"
              />
              {kind === "line" &&
                series.map((s) =>
                  s.values[hovered] === null || s.values[hovered] === undefined ? null : (
                    <circle
                      key={s.id}
                      cx={x(hovered)}
                      cy={y(s.values[hovered] ?? 0)}
                      r={4}
                      fill={s.color}
                      stroke="var(--surface)"
                      strokeWidth={2}
                    />
                  ),
                )}
            </g>
          )}
          <rect
            x={PAD.left}
            y={PAD.top}
            width={plotWidth}
            height={plotHeight}
            fill="transparent"
            onPointerMove={pick}
            onPointerDown={pick}
            onPointerLeave={() => setHover(null)}
          />
          {empty && (
            <text
              x={PAD.left + plotWidth / 2}
              y={PAD.top + plotHeight / 2}
              textAnchor="middle"
              className="fill-muted-foreground text-xs"
            >
              No data in this window
            </text>
          )}
        </svg>
        {hovered !== null && !empty && (
          <Tooltip left={x(hovered)} width={width}>
            <p className="font-mono text-[10px] text-muted-foreground">{labels[hovered]}</p>
            {series.map((s) => (
              <p key={s.id} className="flex items-center gap-2 text-xs">
                <span aria-hidden className="size-2 rounded-full" style={{ background: s.color }} />
                <span className="text-muted-foreground">{s.label}</span>
                <span className="ml-auto pl-3 font-mono font-semibold">
                  {s.values[hovered] === null || s.values[hovered] === undefined
                    ? "–"
                    : format(s.values[hovered] ?? 0)}
                </span>
              </p>
            ))}
          </Tooltip>
        )}
      </div>
    </figure>
  );
}

/** Line-end label heights, nudged apart so labels for lines ending close together don't overlap. */
function endLabelPositions(series: Series[], y: (value: number) => number) {
  const ends = series
    .map((s) => {
      let last: number | null = null;
      for (const value of s.values) if (value !== null) last = value;
      return { id: s.id, at: last === null ? null : y(last) };
    })
    .filter((end): end is { id: string; at: number } => end.at !== null)
    .sort((a, b) => a.at - b.at);
  const placed = new Map<string, number>();
  let previous = -Infinity;
  for (const end of ends) {
    const at = Math.max(end.at, previous + 11);
    placed.set(end.id, at);
    previous = at;
  }
  return placed;
}

function Tooltip({ left, width, children }: { left: number; width: number; children: ReactNode }) {
  const flip = left > width / 2;
  return (
    <div
      className="pointer-events-none absolute top-2 z-10 min-w-36 space-y-0.5 rounded-md border bg-popover px-2.5 py-2 shadow-lg"
      style={flip ? { right: width - left + 10 } : { left: left + 10 }}
    >
      {children}
    </div>
  );
}

/** A labelled number for a dashboard row. `tone` colors the value; the label always says what it is. */
export function Stat({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: "good" | "bad" | "warn" | undefined;
}) {
  return (
    <div className="min-w-0 rounded-lg border bg-surface p-3">
      <dt className="truncate font-mono text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
        {label}
      </dt>
      <dd
        className={cn(
          "mt-1 truncate font-display text-2xl font-bold tabular-nums",
          tone === "good" && "text-win",
          tone === "bad" && "text-loss",
          tone === "warn" && "text-[var(--viz-warning)]",
        )}
      >
        {value}
      </dd>
      {hint && <p className="mt-0.5 truncate text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/** A thin proportion bar for won / lost / washed / open, with the numbers beside it. */
export function SplitBar({
  parts,
  label,
}: {
  parts: { label: string; value: number; color: string }[];
  label: string;
}) {
  const total = parts.reduce((sum, part) => sum + part.value, 0);
  return (
    <div
      role="img"
      aria-label={`${label}: ${parts.map((p) => `${p.value} ${p.label}`).join(", ")}`}
      className="flex h-2.5 w-full gap-0.5 overflow-hidden rounded-full bg-muted"
    >
      {total > 0 &&
        parts.map((part) =>
          part.value > 0 ? (
            <span
              key={part.label}
              title={`${part.label}: ${part.value}`}
              style={{ width: `${(part.value / total) * 100}%`, background: part.color }}
            />
          ) : null,
        )}
    </div>
  );
}

import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** The admin dashboard's building blocks: titled panels and label/value lists, shared by every tab. */

/** A panel's surface, for panels with their own header (a collapsible, a scoreboard). */
export const PANEL = "min-w-0 rounded-xl border bg-surface/60 p-4";

export function PanelTitle({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <h3
      id={id}
      className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
    >
      {children}
    </h3>
  );
}

/** A titled panel; `action` sits at the right of the title (a button, a caption). */
export function Panel({
  title,
  action,
  className,
  children,
}: {
  title: ReactNode;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn(PANEL, "space-y-3", className)}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <PanelTitle>{title}</PanelTitle>
        {action}
      </div>
      {children}
    </section>
  );
}

/** Label/value rows, one under another. */
export function FactList({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="divide-y text-sm">
      {rows.map(([label, value]) => (
        <div key={label} className="flex items-baseline justify-between gap-3 py-1.5">
          <dt className="text-muted-foreground">{label}</dt>
          <dd className="font-mono tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** One label/value row, for a {@code <dl>} laid out by its caller (a grid of them). */
export function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-3 border-b py-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-mono text-xs">{children}</dd>
    </div>
  );
}

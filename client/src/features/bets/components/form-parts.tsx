import type { ReactNode } from "react";
import { Minus, Plus } from "lucide-react";

/** Labeled block inside the proposal sheet. */
export function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="space-y-2.5">
      <h4 className="font-mono text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
        {label}
      </h4>
      {children}
    </section>
  );
}

/** − value + control for lines and counts; `format` renders the value. */
export function Stepper({
  value,
  step,
  min,
  max,
  format = String,
  label,
  onChange,
}: {
  value: number;
  step: number;
  min?: number | undefined;
  max?: number | undefined;
  format?: (v: number) => string;
  /** Names the value for screen readers, e.g. "Total points line". */
  label?: string;
  onChange: (v: number) => void;
}) {
  const clamp = (v: number) =>
    Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min ?? Number.NEGATIVE_INFINITY, v));
  return (
    <div className="flex h-10 items-center rounded-md border bg-surface">
      <button
        type="button"
        className="flex h-full w-10 items-center justify-center text-muted-foreground hover:text-foreground"
        onClick={() => onChange(clamp(value - step))}
        aria-label="Decrease"
      >
        <Minus className="size-4" />
      </button>
      <span
        className="flex-1 text-center scoreboard text-lg text-foreground"
        aria-label={label}
        aria-live="polite"
      >
        {format(value)}
      </span>
      <button
        type="button"
        className="flex h-full w-10 items-center justify-center text-muted-foreground hover:text-foreground"
        onClick={() => onChange(clamp(value + step))}
        aria-label="Increase"
      >
        <Plus className="size-4" />
      </button>
    </div>
  );
}

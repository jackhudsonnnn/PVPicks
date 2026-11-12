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

/** − value + control for whole-number settings (lines, counts). */
export function Stepper({
  value,
  step,
  min,
  onChange,
}: {
  value: number;
  step: number;
  min?: number | undefined;
  onChange: (v: number) => void;
}) {
  const clamp = (v: number) => (min !== undefined ? Math.max(min, v) : v);
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
      <span className="flex-1 text-center scoreboard text-lg text-foreground">{value}</span>
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

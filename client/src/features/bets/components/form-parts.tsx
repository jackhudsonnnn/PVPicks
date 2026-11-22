import type { ReactNode } from "react";
import { Minus, Plus } from "lucide-react";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";

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

/**
 * − value + control for lines and counts; `format` renders the value. `jumps` adds a row of bigger steps under it
 * (e.g. [10, 100] gives −100 −10 +10 +100), so a 220-point total isn't hundreds of taps.
 */
export function Stepper({
  value,
  step,
  min,
  max,
  format = String,
  label,
  jumps,
  onChange,
}: {
  value: number;
  step: number;
  min?: number | undefined;
  max?: number | undefined;
  format?: (v: number) => string;
  /** Names the value for screen readers, e.g. "Total points line". */
  label?: string;
  jumps?: readonly number[];
  onChange: (v: number) => void;
}) {
  const clamp = (v: number) =>
    Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min ?? Number.NEGATIVE_INFINITY, v));
  const steps = jumps
    ? [...jumps]
        .reverse()
        .map((j) => -j)
        .concat(jumps)
    : [];
  return (
    <div className="space-y-1.5">
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
      {steps.length > 0 && (
        <div
          className="grid gap-1.5"
          style={{ gridTemplateColumns: `repeat(${steps.length}, 1fr)` }}
        >
          {steps.map((j) => {
            const next = clamp(value + j);
            return (
              <button
                key={j}
                type="button"
                disabled={next === value}
                onClick={() => onChange(next)}
                aria-label={`${j > 0 ? "Add" : "Subtract"} ${Math.abs(j)}`}
                className="rounded-md border bg-background py-1.5 font-mono text-xs font-bold text-muted-foreground transition-colors hover:border-primary hover:text-foreground disabled:opacity-40 disabled:hover:border-border"
              >
                {j > 0 ? `+${j}` : `−${Math.abs(j)}`}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/** The 4fun switch at the top of every proposal's wager section. */
export function FunToggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (on: boolean) => void;
}) {
  return (
    <div className="flex items-start justify-between gap-4 rounded-xl border bg-surface p-3">
      <span className="min-w-0">
        <span className="block text-sm font-semibold">4fun</span>
        <span className="block text-xs text-muted-foreground">
          No points at stake and nothing tracked. Just bragging rights.
        </span>
      </span>
      <Switch checked={checked} onCheckedChange={onChange} aria-label="4fun bet" />
    </div>
  );
}

/** The box wagers sit in; faded and inert for a 4fun bet. */
export function WagerBox({ off, children }: { off: boolean; children: ReactNode }) {
  return (
    <div
      className={cn(
        "space-y-4 rounded-xl border bg-surface p-3 transition-opacity",
        off && "pointer-events-none opacity-40",
      )}
      aria-disabled={off || undefined}
    >
      {children}
    </div>
  );
}

/** A points amount on a slider: the value large, the slider under it, its range below. */
export function WagerSlider({
  label,
  value,
  min,
  max,
  step = 10,
  disabled,
  onChange,
}: {
  /** Names it on screen ("Minimum") and for screen readers; a lone wager needs none on screen. */
  label?: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  disabled?: boolean;
  onChange: (cents: number) => void;
}) {
  return (
    <div className="space-y-2.5">
      <div className="flex items-baseline justify-between gap-3">
        {label && (
          <span className="font-mono text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
            {label}
          </span>
        )}
        <span
          className={cn("scoreboard text-3xl leading-none text-primary", !label && "mr-auto")}
          aria-live="polite"
        >
          {formatCents(value)}
        </span>
      </div>
      <Slider
        value={[value]}
        min={min}
        max={max}
        step={step}
        disabled={!!disabled}
        thumbLabel={label ? `${label} in points` : "Wager in points"}
        onValueChange={([v]) => v !== undefined && onChange(v)}
      />
      <p className="flex justify-between font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
        <span>Min {formatCents(min)}</span>
        <span>Max {formatCents(max)}</span>
      </p>
    </div>
  );
}

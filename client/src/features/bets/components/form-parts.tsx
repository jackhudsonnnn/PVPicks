import type { ReactNode } from "react";
import { Minus, Plus } from "lucide-react";
import { Slider } from "@/components/ui/slider";
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
 * − value + control for small counts (Jackblack's rules); `format` renders the value, and each button shows its step
 * under it. Lines, targets and wagers use `ValueSlider` instead.
 */
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
  /** Names the value for screen readers, e.g. "Max splits". */
  label?: string;
  onChange: (v: number) => void;
}) {
  const clamp = (v: number) =>
    Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min ?? Number.NEGATIVE_INFINITY, v));
  // Formats the step like the value ("5s"), without the sign a line adds.
  const stepLabel = format(step).replace(/^\+/, "");
  return (
    <div className="flex items-stretch rounded-md border bg-surface">
      <StepButton
        label="Decrease"
        step={stepLabel}
        disabled={clamp(value - step) === value}
        onClick={() => onChange(clamp(value - step))}
      >
        <Minus className="size-4" />
      </StepButton>
      <span
        className="flex flex-1 items-center justify-center scoreboard text-lg text-foreground"
        aria-label={label}
        aria-live="polite"
      >
        {format(value)}
      </span>
      <StepButton
        label="Increase"
        step={stepLabel}
        disabled={clamp(value + step) === value}
        onClick={() => onChange(clamp(value + step))}
      >
        <Plus className="size-4" />
      </StepButton>
    </div>
  );
}

function StepButton({
  label,
  step,
  disabled,
  onClick,
  children,
}: {
  label: string;
  step: string;
  disabled: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className="flex w-12 flex-col items-center justify-center gap-0.5 py-1.5 text-muted-foreground hover:text-foreground disabled:opacity-40 disabled:hover:text-muted-foreground"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
    >
      {children}
      <span className="font-mono text-[10px] leading-none" aria-hidden="true">
        {step}
      </span>
    </button>
  );
}

/**
 * A number on a slider, the same widget for wagers, lines and targets: the value large and green at the top left
 * (`note` can follow it, like the wager's "(4fun)"), the slider, and under it one row: "Min x" and −step on the
 * left, +step and "Max y" on the right, all tappable. `sliderMax` caps the range below `max` where real values sit
 * low (a player's 25 points on a 0–999 slider would be a sliver); Max, + and the slider all stop there.
 */
export function ValueSlider({
  value,
  min,
  max,
  sliderMax,
  step,
  format = String,
  label,
  sliderLabel,
  note,
  onChange,
}: {
  value: number;
  min: number;
  max: number;
  sliderMax?: number | undefined;
  step: number;
  format?: (v: number) => string;
  /** Names the value for screen readers, e.g. "Total points line". */
  label?: string;
  /** Names the slider; defaults to "<label> slider". */
  sliderLabel?: string;
  note?: ReactNode;
  onChange: (v: number) => void;
}) {
  // The shown Max is the ceiling for everything (a value already above it, say from a starting line, stays).
  const top = Math.max(Math.min(sliderMax ?? max, max), value);
  const clamp = (v: number) => Math.min(top, Math.max(min, v));
  const stepLabel = format(step).replace(/^\+/, "");
  const edge =
    "rounded-md px-1.5 py-1 transition-colors hover:bg-muted hover:text-foreground disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-muted-foreground";
  return (
    <div className="space-y-2.5 rounded-xl border bg-surface p-3">
      <p
        className="scoreboard text-3xl leading-none text-primary"
        aria-label={label}
        aria-live="polite"
      >
        {format(value)}
        {note && (
          <span className="ml-1.5 font-mono text-xs font-bold uppercase tracking-wider text-muted-foreground">
            {note}
          </span>
        )}
      </p>
      <Slider
        value={[value]}
        min={min}
        max={top}
        step={step}
        thumbLabel={sliderLabel ?? `${label ?? "Value"} slider`}
        onValueChange={([v]) => v !== undefined && onChange(clamp(v))}
      />
      <div className="flex items-center justify-between gap-2 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
        <span className="flex items-center gap-1">
          <button
            type="button"
            className={edge}
            disabled={value === min}
            onClick={() => onChange(min)}
            aria-label={`${label ? `${label} minimum` : "Minimum"}, ${format(min)}`}
          >
            Min {format(min)}
          </button>
          <button
            type="button"
            className={cn(edge, "font-bold")}
            disabled={clamp(value - step) === value}
            onClick={() => onChange(clamp(value - step))}
            aria-label={label ? `Decrease ${label}` : "Decrease"}
          >
            −{stepLabel}
          </button>
        </span>
        <span className="flex items-center gap-1">
          <button
            type="button"
            className={cn(edge, "font-bold")}
            disabled={clamp(value + step) === value}
            onClick={() => onChange(clamp(value + step))}
            aria-label={label ? `Increase ${label}` : "Increase"}
          >
            +{stepLabel}
          </button>
          <button
            type="button"
            className={edge}
            disabled={value === top}
            onClick={() => onChange(top)}
            aria-label={`${label ? `${label} maximum` : "Maximum"}, ${format(top)}`}
          >
            Max {format(top)}
          </button>
        </span>
      </div>
    </div>
  );
}

/**
 * A range on one slider, two thumbs (e.g. how many players a game takes): the range large and green at the top left
 * like `ValueSlider`, the slider, and the two ends under it. The thumbs can meet but not cross.
 */
export function RangeSlider({
  low,
  high,
  min,
  max,
  label,
  unit,
  onChange,
}: {
  low: number;
  high: number;
  min: number;
  max: number;
  /** Names the range for screen readers, e.g. "Players". */
  label: string;
  /** After the numbers, e.g. "players". */
  unit: string;
  onChange: (low: number, high: number) => void;
}) {
  return (
    <div className="space-y-2.5 rounded-xl border bg-surface p-3">
      <p className="scoreboard text-3xl leading-none text-primary" aria-live="polite">
        {low === high ? low : `${low}–${high}`}
        <span className="ml-1.5 font-mono text-xs font-bold uppercase tracking-wider text-muted-foreground">
          {unit}
        </span>
      </p>
      <Slider
        value={[low, high]}
        min={min}
        max={max}
        step={1}
        minStepsBetweenThumbs={0}
        thumbLabel={[`Fewest ${label.toLowerCase()}`, `Most ${label.toLowerCase()}`]}
        onValueChange={([a, b]) => a !== undefined && b !== undefined && onChange(a, b)}
      />
      <div className="flex justify-between font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
        <span>Min {min}</span>
        <span>Max {max}</span>
      </div>
    </div>
  );
}

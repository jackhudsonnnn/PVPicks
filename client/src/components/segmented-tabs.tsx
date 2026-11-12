import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ComponentType,
  type CSSProperties,
  type KeyboardEvent,
} from "react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { motion } from "motion/react";
import { cn } from "@/lib/utils";

export interface SegmentedTab<T extends string> {
  id: T;
  label: string;
  icon?: ComponentType<{ className?: string }>;
  /** Small number shown after the label (e.g. ticket counts). */
  count?: number;
  /** Shown greyed out with a small "Soon" tag. */
  disabled?: boolean;
}

/** Spring for the sliding highlight. The app-wide MotionConfig turns it off for reduced motion. */
const SLIDE = { type: "spring", stiffness: 500, damping: 40, mass: 0.8 } as const;

/**
 * The one toggle/tab strip used across the app (page tabs, filters, option pickers).
 * The green highlight slides between options; it snaps instead when animations are reduced.
 *
 * - `size="lg"`  — chunky display font, for page-level tabs.
 * - `size="sm"`  — compact mono labels, for panels and inline choices.
 */
export function SegmentedTabs<T extends string>({
  tabs,
  value,
  onChange,
  size = "lg",
  isActive,
  className,
  ariaLabel,
  narrowColumns,
  fitContent,
  dropdownOnly = false,
}: {
  tabs: readonly SegmentedTab<T>[];
  value: T;
  onChange: (id: T) => void;
  size?: "sm" | "lg";
  /** Override which tab looks selected (e.g. "chat" stays lit when value is "table" on desktop). */
  isActive?: (id: T) => boolean;
  className?: string;
  ariaLabel?: string;
  /** Columns on narrow screens (below `md`), e.g. 2 turns four tabs into a 2x2 grid. */
  narrowColumns?: number;
  /** Size each option to its label instead of equal widths, so long labels fit on phones. */
  fitContent?: boolean;
  /** Compact selector for page-header actions. */
  dropdownOnly?: boolean;
}) {
  const highlightId = `segmented-${useId()}`;
  const wide = tabs.length;
  const narrow = narrowColumns ?? wide;
  // When any label would be cut off, swap the strip for a dropdown. The strip stays
  // rendered invisibly so we can keep measuring as the screen size changes.
  const stripRef = useRef<HTMLDivElement>(null);
  const [autoCollapsed, setCollapsed] = useState(false);
  const collapsed = dropdownOnly || autoCollapsed;
  useLayoutEffect(() => {
    const el = stripRef.current;
    if (!el || dropdownOnly) return;
    const check = () => {
      const labels = el.querySelectorAll<HTMLElement>("[data-tab-label]");
      setCollapsed(Array.from(labels).some((l) => l.scrollWidth > l.clientWidth + 1));
    };
    check();
    const ro = new ResizeObserver(check);
    ro.observe(el);
    return () => ro.disconnect();
  }, [tabs, dropdownOnly]);
  const activeId = tabs.find((t) => (isActive ? isActive(t.id) : value === t.id))?.id ?? value;
  // Tab-strip keyboard pattern: one Tab stop, arrow keys / Home / End move between options.
  const enabled = tabs.filter((t) => !t.disabled);
  const focusable = enabled.some((t) => t.id === activeId) ? activeId : enabled[0]?.id;
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const i = enabled.findIndex((t) => t.id === activeId);
    const to =
      e.key === "ArrowRight" || e.key === "ArrowDown"
        ? enabled[(i + 1) % enabled.length]
        : e.key === "ArrowLeft" || e.key === "ArrowUp"
          ? enabled[(i - 1 + enabled.length) % enabled.length]
          : e.key === "Home"
            ? enabled[0]
            : e.key === "End"
              ? enabled[enabled.length - 1]
              : undefined;
    if (!to) return;
    e.preventDefault();
    onChange(to.id);
    stripRef.current?.querySelector<HTMLElement>(`[data-tab-id="${CSS.escape(to.id)}"]`)?.focus();
  }
  return (
    <div className="relative">
      {collapsed && (
        <Select value={activeId} onValueChange={(v) => onChange(v as T)}>
          <SelectTrigger aria-label={ariaLabel} className={cn("h-11 w-full bg-surface", className)}>
            {/* Hidden copies of every label keep the box as wide as the longest one. */}
            <div className="grid min-w-0 text-left">
              {tabs.map(({ id, label, count }) => (
                <span key={id} aria-hidden className="invisible col-start-1 row-start-1 h-0 truncate">
                  {label}
                  {count !== undefined && ` (${count})`}
                </span>
              ))}
              <span className="col-start-1 row-start-1 min-w-0 truncate">
                <SelectValue />
              </span>
            </div>
          </SelectTrigger>
          <SelectContent>
            {tabs.map(({ id, label, icon: Icon, count, disabled }) => (
              <SelectItem key={id} value={id} disabled={!!disabled}>
                <span className="flex items-center gap-2">
                  {Icon && <Icon className="size-4 shrink-0" />}
                  {label}
                  {count !== undefined && <span className="opacity-70">({count})</span>}
                  {disabled && <span className="text-xs text-muted-foreground">Soon</span>}
                </span>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
      {!dropdownOnly && <div
        ref={stripRef}
        aria-hidden={collapsed || undefined}
        role={collapsed ? undefined : "tablist"}
        aria-label={ariaLabel}
        onKeyDown={onKeyDown}
        className={cn(
          "gap-1 rounded-xl bg-sidebar p-1.5",
          fitContent
            ? "flex"
            : "grid grid-cols-[repeat(var(--tabs-narrow),minmax(0,1fr))] md:grid-cols-[repeat(var(--tabs-wide),minmax(0,1fr))]",
          className,
          collapsed && "pointer-events-none invisible absolute inset-x-0 top-0",
        )}
        style={{ "--tabs-narrow": narrow, "--tabs-wide": wide } as CSSProperties}
      >
        {tabs.map(({ id, label, icon: Icon, count, disabled }) => {
          const on = isActive ? isActive(id) : value === id;
          return (
            <button
              key={id}
              type="button"
              role="tab"
              data-tab-id={id}
              aria-selected={on}
              tabIndex={id === focusable ? 0 : -1}
              disabled={disabled}
              onClick={() => onChange(id)}
              className={cn(
                "relative isolate flex min-w-0 items-center justify-center rounded-lg transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-50",
                size === "lg"
                  ? "py-2 font-display text-base font-bold uppercase tracking-wide"
                  : "py-1.5 font-mono text-[11px] font-bold uppercase tracking-widest",
                fitContent
                  ? "flex-auto gap-1 px-1 tracking-normal sm:px-2 sm:tracking-widest"
                  : "gap-2",
                on
                  ? "text-primary-foreground"
                  : "text-muted-foreground enabled:hover:text-foreground",
              )}
            >
              {on && (
                <motion.span
                  layoutId={highlightId}
                  transition={SLIDE}
                  aria-hidden
                  className="absolute inset-0 -z-10 rounded-lg bg-primary"
                />
              )}
              {Icon && <Icon className={cn("shrink-0", size === "lg" ? "size-4" : "size-3.5")} />}
              <span data-tab-label className="truncate">
                {label}
              </span>
              {count !== undefined && (
                <span className="shrink-0 font-mono text-[11px] opacity-70">{count}</span>
              )}
              {disabled && (
                <span className="rounded bg-muted px-1 font-mono text-[8px] tracking-wider text-muted-foreground">
                  Soon
                </span>
              )}
            </button>
          );
        })}
      </div>}
    </div>
  );
}

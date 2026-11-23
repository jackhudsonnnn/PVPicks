import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { Table } from "@/features/tables";
import { MODES, type ModeDef } from "@/features/bets/modes";
import { holdemBlock, jackblackBlock, u2pickBlock } from "@/features/bets/ticket-model";

const LEAGUE_BADGES = [
  { id: "nfl", label: "NFL", className: "border-primary/30 bg-primary/10 text-primary" },
  {
    id: "nba",
    label: "NBA",
    className: "border-status-pending/30 bg-status-pending/10 text-status-pending",
  },
];

/**
 * The proposal sheet's first step: every mode as a card. A mode is pickable when the server says it can be proposed
 * and this table allows it (`block`, or U2Pick turned off); otherwise the card says why.
 */
export function ModeGrid({
  table,
  userId,
  availability,
  block,
  onPick,
}: {
  table: Table;
  userId: string;
  /** From `useModeAvailability`, loaded with the sheet so it's ready when the grid opens. */
  availability: ReadonlyMap<string, boolean> | undefined;
  /** Why the viewer can't propose anything here, or null. */
  block: string | null;
  onPick: (mode: ModeDef) => void;
}) {
  return (
    <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
      {MODES.map((m) => {
        const Icon = m.icon;
        const available = availability?.get(m.id) ?? false;
        const modeBlock =
          block ??
          (m.custom
            ? u2pickBlock(table)
            : m.cardGame === "jackblack"
              ? jackblackBlock(table, userId)
              : m.cardGame === "holdem"
                ? holdemBlock(table, userId)
                : null);
        const off = !available || !!modeBlock;
        return (
          <Button
            variant="outline"
            key={m.id}
            disabled={off}
            onClick={() => onPick(m)}
            className="group flex h-auto flex-col items-start gap-3 whitespace-normal rounded-xl border bg-surface p-4 text-left transition-all enabled:hover:border-primary enabled:hover:bg-surface-raised disabled:cursor-not-allowed disabled:opacity-50"
          >
            <span className="flex w-full items-center justify-between gap-2">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
                <Icon className="size-5" />
              </span>
              {!m.custom && !m.cardGame && (
                <span className="flex flex-wrap justify-end gap-1">
                  {LEAGUE_BADGES.map((l) => (
                    <span
                      key={l.id}
                      className={cn(
                        "rounded-full border px-2 py-0.5 font-mono text-[10px] font-bold",
                        l.className,
                      )}
                    >
                      {l.label}
                    </span>
                  ))}
                </span>
              )}
            </span>
            <span>
              <span className="block font-display text-lg font-bold uppercase leading-none">
                {m.name}
              </span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {!available ? "Coming soon" : (modeBlock ?? m.tagline)}
              </span>
            </span>
          </Button>
        );
      })}
    </div>
  );
}

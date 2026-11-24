import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import { useBetLiveStats } from "@/features/bets/live-stats";

/**
 * A pending sports ticket's sweat (owner idea, 2026-11-24): how the numbers it follows are doing right now, on the
 * ticket itself rather than only in Details. A bar toward the line or target (Prop Hunt, Total Disaster, King Of The
 * Hill), a tug between the two players (Either Or), or the margin against the spread (Give And Take). Scorcerer has
 * nothing to fill, so it shows nothing. Text wraps across the card when it's long.
 */
export function TicketProgress({ bet }: { bet: Bet }) {
  const pending = bet.status === "PENDING" && !!bet.gameId && bet.modeId !== "scorcerer";
  const live = useBetLiveStats(bet.id, pending);
  if (!pending || !live.data || live.data.rows.length === 0) return null;
  const rows = live.data.rows;

  if (bet.modeId === "either_or" && rows.length === 2) {
    const [a, b] = rows as [(typeof rows)[0], (typeof rows)[0]];
    const x = a.current ?? 0;
    const y = b.current ?? 0;
    const share = x + y === 0 ? 50 : (x / (x + y)) * 100;
    return (
      <section aria-label="Right now" className="space-y-1.5">
        <p className="flex flex-wrap justify-between gap-x-3 text-xs font-semibold">
          <span>
            {a.label} <span className="scoreboard text-sm">{x}</span>
          </span>
          <span>
            <span className="scoreboard text-sm">{y}</span> {b.label}
          </span>
        </p>
        <div className="flex h-1.5 overflow-hidden rounded-full bg-ticket-foreground/10">
          <div
            className="bg-primary transition-[width] duration-700"
            style={{ width: `${share}%` }}
          />
          <div className="flex-1 bg-ticket-foreground/40" />
        </div>
      </section>
    );
  }

  if (bet.modeId === "give_and_take") {
    const row = rows[0]!;
    const margin = row.current ?? 0;
    const spread = row.target ?? 0;
    const covering = margin + spread > 0;
    const team = row.label.replace(/ margin$/, "");
    return (
      <p aria-label="Right now" className="text-xs font-semibold">
        {team} by <span className="scoreboard text-sm">{margin > 0 ? `+${margin}` : margin}</span>{" "}
        <span className="text-ticket-muted">
          (spread {spread > 0 ? `+${spread}` : spread}) ·{" "}
          {covering ? `${team} covering` : `${team} not covering`}
        </span>
      </p>
    );
  }

  return (
    <section aria-label="Right now" className="space-y-2">
      {rows
        .filter((row) => row.target !== null)
        .map((row) => {
          const now = row.current ?? 0;
          const target = row.target!;
          const fill = Math.min(100, Math.max(0, (now / target) * 100));
          return (
            <div key={row.label} className="space-y-1">
              <p className="flex flex-wrap items-baseline justify-between gap-x-3 text-xs font-semibold">
                <span>{row.label}</span>
                <span>
                  <span className="scoreboard text-sm">{now}</span>
                  <span className="text-ticket-muted"> / {target}</span>
                </span>
              </p>
              <div className="h-1.5 overflow-hidden rounded-full bg-ticket-foreground/10">
                <div
                  className={cn(
                    "h-full rounded-full transition-[width] duration-700",
                    now > target ? "bg-win" : "bg-primary",
                  )}
                  style={{ width: `${fill}%` }}
                />
              </div>
            </div>
          );
        })}
    </section>
  );
}

import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";

/**
 * The bottom of every ticket (pick modes, sports and Jackblack alike): the table it's at, linked unless it's a
 * preview, and the ticket's numbers on the right ("Wager · Participants" or "Pot" after lock, "Ante · Participants").
 */
export function TicketFooter({
  table,
  preview,
  stats,
}: {
  table: { id: string; name: string; emoji: string };
  preview: boolean;
  stats: { label: string; value: ReactNode }[];
}) {
  return (
    <div className="flex items-center justify-between gap-3 border-t border-ticket-foreground/10 pt-3">
      <p className="flex min-w-0 items-center gap-1.5 text-xs">
        <span
          aria-hidden
          className="flex size-6 shrink-0 items-center justify-center rounded-full bg-ticket-foreground/10 text-sm"
        >
          {table.emoji}
        </span>
        {preview ? (
          <span className="truncate font-semibold text-ticket-foreground">{table.name}</span>
        ) : (
          <Link
            to="/tables/$tableId"
            params={{ tableId: table.id }}
            className="truncate font-semibold text-ticket-foreground underline underline-offset-2"
          >
            {table.name}
          </Link>
        )}
      </p>
      <dl className="flex gap-4 text-right font-mono text-[11px] uppercase tracking-wider text-ticket-muted">
        {stats.map((stat) => (
          <div key={stat.label}>
            <dt>{stat.label}</dt>
            <dd className="scoreboard text-base text-ticket-foreground">{stat.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

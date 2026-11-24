import type { ReactNode } from "react";
import type { SportsGame } from "./dashboard-api";
import { Chip } from "./players";

/** Small pieces the sports explorer and its game inspector share. */

/** Live games refresh this often; everything else on demand. */
export const LIVE_MS = 5000;

export function Hint({ children }: { children: ReactNode }) {
  return <p className="p-2 text-sm text-muted-foreground">{children}</p>;
}

export function StatusChip({ status }: { status: SportsGame["status"] }) {
  return (
    <Chip
      tone={
        status === "LIVE"
          ? "bad"
          : status === "FINAL"
            ? "muted"
            : status === "POSTPONED"
              ? "warn"
              : "muted"
      }
    >
      {status.toLowerCase()}
    </Chip>
  );
}

export function periodName(league: string, period: number | null) {
  if (period === null) return "";
  const regulation = 4;
  return period > regulation
    ? `OT${period - regulation > 1 ? period - regulation : ""}`
    : `Q${period}`;
}

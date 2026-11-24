import type { ReactNode } from "react";
import type { SportsGame } from "./dashboard-api";
import { Chip } from "./players";

/** Small pieces the sports explorer and its game inspector share. */

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

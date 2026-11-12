import type { Hue } from "@/features/settings/profile";

export interface User {
  id: string;
  /** Unique (case-insensitive) and shown everywhere, bare (no "@"). */
  username: string;
  /** Index into the chart-1..5 palette. */
  hue: Hue;
  /** Account was deleted: shown as "Deleted user"; their past results stay on the ledger. */
  deleted?: boolean;
}

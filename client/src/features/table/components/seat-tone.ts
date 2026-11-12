import type { SeatTone } from "../seating";

/** Chip colors for a seat status (In, Pass, a pick, Won/Lost, Washed). */
export const SEAT_TONE: Record<SeatTone, string> = {
  in: "bg-primary text-primary-foreground",
  pass: "bg-muted text-muted-foreground",
  pick: "bg-ticket text-ticket-foreground",
  win: "bg-win text-primary-foreground",
  loss: "bg-loss text-destructive-foreground",
  washed: "bg-status-washed text-primary-foreground",
};

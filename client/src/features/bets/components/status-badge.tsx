import { cva } from "class-variance-authority";
import { cn } from "@/lib/utils";
import type { BetStatus } from "../types";

const badge = cva(
  "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 font-mono text-[10px] font-bold uppercase tracking-widest",
  {
    variants: {
      status: {
        active: "bg-status-active text-primary-foreground",
        pending: "bg-status-pending text-primary-foreground",
        washed: "bg-status-washed text-primary-foreground",
        resolved: "bg-status-resolved text-primary-foreground",
      },
    },
  },
);

const LABEL: Record<BetStatus, string> = {
  active: "Active",
  pending: "Pending",
  washed: "Washed",
  resolved: "Resolved",
};

export function StatusBadge({ status, className }: { status: BetStatus; className?: string }) {
  return (
    <span className={cn(badge({ status }), className)}>
      {(status === "active" || status === "pending") && (
        <span className="size-1.5 animate-pulse-dot rounded-full bg-primary-foreground" />
      )}
      {LABEL[status]}
    </span>
  );
}

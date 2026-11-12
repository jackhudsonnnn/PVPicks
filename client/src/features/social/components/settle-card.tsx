import { cn } from "@/lib/utils";
import { useGetUser } from "@/features/store";
import { UserAvatar } from "./user-avatar";
import type { SettleGroup } from "../settle-groups";
import { SettleReceiptsDialog } from "./settle-receipts-dialog";
import { formatPts } from "@/features/bets/ledger";

/** Person summary; actions belong to individual dated receipts in the popup. */
export function SettleCard({ group }: { group: SettleGroup }) {
  const getUser = useGetUser();
  const owe = group.net < 0;
  const other = group.otherUserId;
  return (
    <div
      className={cn(
        "flex min-h-48 w-60 flex-col gap-2 rounded-lg border p-3",
        owe ? "border-loss/50 bg-loss/10" : group.net > 0 ? "border-primary/40 bg-primary/5" : "border-border bg-surface",
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <UserAvatar userId={other} size="md" />
        <div className="min-w-0 flex-1">
          <p className="break-words font-semibold leading-tight">{getUser(other).username}</p>
          <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            {group.net === 0 ? "You're square" : owe ? "You pay them" : "They pay you"}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">{group.tables.length} {group.tables.length === 1 ? "table" : "tables"} · {group.payments.length} open</p>
        </div>
      </div>
      <div className="flex items-baseline justify-between gap-2">
        <span className={cn("scoreboard text-3xl leading-none", owe ? "text-loss" : group.net > 0 ? "text-win" : "text-muted-foreground")}>
          {owe ? "−" : group.net > 0 ? "+" : ""}${formatPts(Math.abs(group.net))}
        </span>
        {group.payments.some((p) => p.status === "sent") && <span className="text-[10px] text-status-pending">Awaiting receipt</span>}
      </div>
      <div className="mt-auto">
        <SettleReceiptsDialog otherUserId={other} />
      </div>
    </div>
  );
}

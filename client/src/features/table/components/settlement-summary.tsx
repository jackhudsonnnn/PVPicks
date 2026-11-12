import { SettleLines } from "./settle-lines";
import { cn } from "@/lib/utils";
import { formatPts } from "@/features/bets/ledger";
import { settlementLines } from "@/features/bets/payout";
import { ScrollContainer } from "@/components/scroll-container";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import type { Table } from "@/features/table/types";

/** Read-only preview of who sends what through `hostId` if points were settled now. */
export function SettlementSummary({ table, hostId }: { table: Table; hostId: string }) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const { lines, hostNet } = settlementLines(state.bets, table.id, hostId);
  const open = state.bets.filter(
    (b) => b.tableId === table.id && (b.status === "active" || b.status === "pending"),
  ).length;
  const first = (id: string) => (id === me ? "You" : getUser(id).username.split(" ")[0]);
  const hostName = first(hostId);

  if (lines.length === 0 && hostNet === 0) {
    return <p className="text-sm text-muted-foreground">Everyone's at 0, nothing to settle.</p>;
  }
  return (
    <>
      <ScrollContainer isVisible={false} className="max-h-56">
        <SettleLines lines={lines} hostId={hostId} />
      </ScrollContainer>
      <p className="text-xs text-muted-foreground">
        {hostName === "You" ? "You net" : `${hostName} nets`}{" "}
        <span className={cn("font-bold", hostNet >= 0 ? "text-win" : "text-loss")}>
          {formatPts(hostNet, true)} pts
        </span>
        {open > 0 &&
          ` · ${open} open ticket${open === 1 ? "" : "s"} stay in play and aren't included`}
      </p>
    </>
  );
}

import { useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { Check, CheckCheck, Clock3, HeartHandshake, ReceiptText, Send, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedList, AnimatedItem } from "@/components/animated-list";
import { useAppStore, useGetUser } from "@/features/store";
import { PaymentActions } from "@/features/table/components/settlement-card";
import { formatPts } from "@/features/bets/ledger";
import { settlePairKey } from "../settle-changes";
import { isClosed, receiptNet, settleReceipts } from "../settle-receipts";
import { UserAvatar } from "./user-avatar";
import { cn } from "@/lib/utils";

export function SettleReceiptsDialog({ otherUserId }: { otherUserId: string }) {
  const { state } = useAppStore();
  const getUser = useGetUser();
  const [visible, setVisible] = useState(30);
  const rows = useMemo(() => settleReceipts(state.messages, state.meId, otherUserId), [state.messages, state.meId, otherUserId]);
  const net = receiptNet(rows, state.meId);
  const change = state.settleChanges?.[settlePairKey(state.meId, otherUserId)];
  return <Dialog onOpenChange={() => setVisible(30)}>
    <DialogTrigger asChild><Button variant="outline" size="sm" className="w-full"><ReceiptText />Settle-up receipts</Button></DialogTrigger>
    <DialogContent className="flex max-w-2xl flex-col overflow-hidden p-4 sm:p-6">
      <DialogHeader className="pr-6 text-left">
        <DialogTitle>Settle-up with {getUser(otherUserId).username}</DialogTitle>
        <DialogDescription>Outstanding net <span className={cn("font-mono font-semibold", net < 0 ? "text-loss" : net > 0 ? "text-win" : "text-muted-foreground")}>{net < 0 ? "−" : net > 0 ? "+" : ""}${formatPts(Math.abs(net))}</span></DialogDescription>
      </DialogHeader>
      {change && <p role="status" className="flex gap-2 border-l-2 border-status-pending bg-status-pending/10 p-3 text-xs"><TriangleAlert className="size-4 shrink-0 text-status-pending" />Balance changed. Pending payment or forgiveness was reset. Check any money already sent before sending again.</p>}
      <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_4.5rem] gap-2 border-b pb-2 text-center text-xs font-semibold sm:grid-cols-[minmax(0,1fr)_6rem_6rem]">
        <span className="text-left text-muted-foreground">Table / amount</span>
        <span className="flex items-center justify-center gap-1"><Send className="size-3" />Sent</span>
        <span className="flex items-center justify-center gap-1"><CheckCheck className="size-3" />Received</span>
      </div>
      <ScrollContainer isVisible={false} className="max-h-[55dvh] divide-y divide-border">
        <AnimatedList>{rows.slice(0, visible).map((p) => {
          const table = state.tables.find((t) => t.id === p.tableId);
          const closed = isClosed(p);
          const sent = p.status === "sent" || p.status === "paid";
          const payerIsMe = p.from === state.meId;
          return <AnimatedItem key={`${p.messageId}:${p.lineUserId}`} layout={false} className={cn("py-3", closed && "opacity-65")}>
            <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_4.5rem] items-start gap-2 sm:grid-cols-[minmax(0,1fr)_6rem_6rem]">
              <div className="min-w-0 space-y-1">
                {table ? <Link to="/tables/$tableId" params={{ tableId: table.id }} className="block break-words text-sm font-semibold hover:underline">{table.emoji} {table.name}</Link> : <p className="text-sm font-semibold">Deleted table</p>}
                <p className="text-[11px] text-muted-foreground">{p.at}</p>
                <p className={cn("font-mono font-bold", payerIsMe ? "text-loss" : "text-win")}>{payerIsMe ? "−" : "+"}${formatPts(p.amount)}</p>
                <p className="text-[11px] text-muted-foreground">{closed ? p.status === "forgiven" ? "Forgiven" : "Completed" : payerIsMe ? "You pay them" : "They pay you"}</p>
              </div>
              {[p.from, p.to].map((userId, column) => <div key={`${userId}:${column}`} className="flex min-w-0 flex-col items-center gap-1 text-center">
                <UserAvatar userId={userId} size="xs" />
                <span className="w-full truncate text-[10px]" title={getUser(userId).username}>{getUser(userId).username}</span>
                {p.status === "forgiven" ? <HeartHandshake aria-label="Forgiven" className="size-5 text-muted-foreground" /> : (column === 0 ? sent : p.status === "paid") ? <Check aria-label={column === 0 ? "Sent" : "Received"} className="size-5 text-win" /> : userId === state.meId ? <PaymentActions p={p} iconOnly /> : <Clock3 aria-label="Waiting" className="size-4 text-muted-foreground" />}
              </div>)}
            </div>
          </AnimatedItem>;
        })}</AnimatedList>
        {visible < rows.length && <Button variant="ghost" size="sm" className="mt-2 w-full" onClick={() => setVisible((n) => n + 30)}>Older receipts</Button>}
      </ScrollContainer>
    </DialogContent>
  </Dialog>;
}
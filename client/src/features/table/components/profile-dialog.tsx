import { useMemo, useState } from "react";

import { cn } from "@/lib/utils";

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useAppStore, useMe, useGetUser } from "@/features/store";
import { formatPts, netResult, record } from "@/features/bets/ledger";
import { StatusBadge } from "@/features/bets/components/status-badge";
import { getMode } from "@/features/bets/modes";
import { PASS, type Bet } from "@/features/bets/types";

import type { Table } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { SafetyButtons } from "@/features/social/components/safety-dialogs";
import { TomatoThrowEffect } from "@/features/social/components/tomato-throw-button";
import { ScrollContainer } from "@/components/scroll-container";


import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { myTables } from "@/features/store/selectors";
import { SettleReceiptsDialog } from "@/features/social/components/settle-receipts-dialog";
import { isClosed, receiptNet, settleReceipts } from "@/features/social/settle-receipts";
import type { Payment } from "../settlements";

const ALL = "all";
type ProfileTab = "bets" | "settlements";

export function ProfileDialog({
  userId,
  table,
  onOpenChange,
  onThrow,
}: {
  userId: string | null;
  table: Table;
  onOpenChange: (o: boolean) => void;
  /** Reveal the table before the effect measures its destination. */
  onThrow: () => void;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [tomato, setTomato] = useState<{ userId: string; name: string } | null>(null);
  const [tab, setTab] = useState<ProfileTab>("bets");
  // Defaults to the table you're in; "all" spans every table you share.
  const [scope, setScope] = useState<string>(table.id);
  const u = userId ? getUser(userId) : null;
  const self = userId === me;
  const tables = myTables(state);
  const scopeTable = scope === ALL ? null : (tables.find((t) => t.id === scope) ?? table);
  const scopedBets = useMemo(
    () => (scope === ALL ? state.bets : state.bets.filter((b) => b.tableId === scope)),
    [state.bets, scope],
  );
  const receipts = useMemo(
    () =>
      userId && !self
        ? settleReceipts(state.messages, me, userId).filter((p) => scope === ALL || p.tableId === scope)
        : [],
    [state.messages, me, userId, self, scope],
  );
  const net = receiptNet(receipts, me);

  return (
    <>
      <Dialog
        open={!!userId}
        onOpenChange={(o) => {
          if (o) return onOpenChange(o);
          onOpenChange(false);
          setTab("bets");
          setScope(table.id);
        }}
      >
        <DialogContent className="flex max-h-[85dvh] flex-col gap-4">
          {u && userId && (
            <>
              <DialogHeader className="flex-row items-center gap-4 space-y-0 text-left">
                <span className="shrink-0">
                  <UserAvatar userId={u.id} size="lg" className="ring-0" />
                </span>
                <div className="min-w-0">
                  <DialogTitle className="break-words font-display text-3xl font-black uppercase leading-none">
                    {self ? "You" : u.username}
                  </DialogTitle>
                  {!self && (
                    <DialogDescription className="mt-1 font-mono text-xs">
                      <span
                        className={
                          net > 0 ? "text-win" : net < 0 ? "text-loss" : "text-muted-foreground"
                        }
                      >
                        {formatPts(net, true)} settle-ups
                      </span>
                    </DialogDescription>
                  )}
                  <ProfileStats userId={userId} bets={scopedBets} allTables={scope === ALL} />
                </div>
              </DialogHeader>

              <SafetyButtons
                userId={userId}
                tableId={scopeTable?.id}
                throwDisabled={scope === ALL}
                onThrow={() => {
                  setTomato({ userId, name: u.username });
                  onOpenChange(false);
                  onThrow();
                }}
              />

              <div className="grid gap-2">
                <SegmentedTabs
                  size="sm"
                  ariaLabel="Profile history"
                  tabs={
                    self
                      ? [{ id: "bets", label: "Bets" }]
                      : [
                          { id: "bets", label: "Bets" },
                          { id: "settlements", label: "Settlements" },
                        ]
                  }
                  value={self ? "bets" : tab}
                  onChange={setTab}
                />
                <SegmentedTabs
                  dropdownOnly
                  size="sm"
                  ariaLabel="Table"
                  tabs={[
                    { id: ALL, label: "All tables" },
                    ...tables.map((t) => ({ id: t.id, label: t.name })),
                  ]}
                  value={scope}
                  onChange={setScope}
                />
              </div>

              <ScrollContainer isVisible={false} className="flex-1">
                {tab === "settlements" && !self ? (
                  <SettlementHistory rows={receipts} otherUserId={userId} />
                ) : (
                  <BetHistory userId={userId} bets={scopedBets} showTable={scope === ALL} />
                )}
              </ScrollContainer>
            </>
          )}
        </DialogContent>
      </Dialog>
      {tomato && (
        <TomatoThrowEffect
          key={tomato.userId}
          userId={tomato.userId}
          name={tomato.name}
          onComplete={() => setTomato(null)}
        />
      )}
    </>
  );
}

function SettlementHistory({ rows, otherUserId }: { rows: Payment[]; otherUserId: string }) {
  const me = useMe();
  const { state } = useAppStore();
  return (
    <div className="grid gap-2">
      {rows.length === 0 ? (
        <EmptyState boxed className="rounded-lg p-6">
          No settle-ups here yet.
        </EmptyState>
      ) : (
        <ul className="grid gap-1.5">
          {rows.map((p) => {
            const t = state.tables.find((x) => x.id === p.tableId);
            const mine = p.to === me;
            const label =
              p.status === "paid"
                ? "Completed"
                : p.status === "forgiven"
                  ? "Forgiven"
                  : p.status === "sent"
                    ? "Awaiting confirmation"
                    : mine
                      ? "They pay you"
                      : "You pay them";
            return (
              <li
                key={`${p.messageId}:${p.lineUserId}`}
                className={cn(
                  "flex items-center justify-between gap-2 rounded-lg border bg-surface px-3 py-2.5",
                  isClosed(p) && "opacity-65",
                )}
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold">
                    {t ? `${t.emoji} ${t.name}` : "Deleted table"}
                  </p>
                  <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                    {p.at} · {label}
                  </p>
                </div>
                <span className={cn("scoreboard text-base", mine ? "text-win" : "text-loss")}>
                  {mine ? "+" : "−"}${formatPts(p.amount)}
                </span>
              </li>
            );
          })}
        </ul>
      )}
      <SettleReceiptsDialog otherUserId={otherUserId} />
    </div>
  );
}

function BetHistory({
  userId,
  bets,
  showTable,
}: {
  userId: string;
  bets: Bet[];
  showTable: boolean;
}) {
  const me = useMe();
  const { state } = useAppStore();
  const history = bets.filter((b) => b.participants.some((p) => p.userId === userId)).reverse();
  if (history.length === 0)
    return (
      <EmptyState boxed className="rounded-lg p-6">
        No bets here yet.
      </EmptyState>
    );
  return (
    <ul className="grid gap-1.5">
      {history.map((b) => {
        const p = b.participants.find((x) => x.userId === userId)!;
        const hidden = b.status === "active" && userId !== me && p.selection !== PASS;
        const d = b.deltas?.[userId];
        return (
          <li key={b.id} className="rounded-lg border bg-surface px-3 py-2.5">
            <div className="flex items-center justify-between gap-2">
              <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                {showTable && `${state.tables.find((t) => t.id === b.tableId)?.name ?? "Table"} · `}
                {getMode(b.modeId).name} · {b.createdAt}
              </span>
              <StatusBadge status={b.status} className="px-1.5 py-0.5 text-[8px]" />
            </div>
            <p className="mt-1 text-sm font-semibold leading-snug">{b.question}</p>
            <div className="mt-1.5 flex items-center justify-between text-xs">
              <span className="text-muted-foreground">
                Pick:{" "}
                <span className="font-semibold text-foreground">
                  {p.selection === PASS ? "No guess" : hidden ? "Hidden until lock" : p.selection}
                </span>
              </span>
              {d !== undefined && (
                <span className={cn("scoreboard text-base", d > 0 ? "text-win" : "text-loss")}>
                  {formatPts(d, true)}
                </span>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** Small Record / Net here line under the name. */
function ProfileStats({
  userId,
  bets,
  allTables,
}: {
  userId: string;
  bets: Bet[];
  allTables: boolean;
}) {
  const rec = record(userId, bets);
  const net = netResult(userId, bets);
  return (
    <p className="mt-1.5 font-mono text-[11px] uppercase tracking-wider text-muted-foreground">
      Record{" "}
      <span className="font-bold text-foreground">
        {rec.w}–{rec.l}
      </span>{" "}
      · {allTables ? "Net" : "Net here"}{" "}
      <span
        className={cn(
          "font-bold",
          net > 0 ? "text-win" : net < 0 ? "text-loss" : "text-foreground",
        )}
      >
        {formatPts(net, true)}
      </span>
    </p>
  );
}

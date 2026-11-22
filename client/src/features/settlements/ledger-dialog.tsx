import { useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { BookOpen, TriangleAlert } from "lucide-react";
import { ActionStatus } from "@/components/action-status";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ConfirmAction } from "@/components/confirm-action";
import { EmptyState } from "@/components/empty-state";
import { LoadMore } from "@/components/load-more";
import { LoadingList } from "@/components/loading";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { backendErrorMessage } from "@/lib/api-client";
import { dateTimeLabel } from "@/lib/time";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { cn } from "@/lib/utils";
import { type User, usePerson } from "@/features/people";
import { useTableNames } from "@/features/tables";
import type { useSettlementActions } from "./actions";
import {
  formatPoints,
  listSettles,
  type Settle,
  type SettleKind,
  type SettlementGroup,
  type SettlementLine,
} from "./api";
import { hoursLeft, selectionActions, selectionNet } from "./ledger-model";

export type Actions = ReturnType<typeof useSettlementActions>;
type Tab = "settle" | "history";

interface LedgerProps {
  user: User;
  expire: () => void;
  /** Everything open with this person: receipts, net, any balance change to review, settles still in their window. */
  group: SettlementGroup;
  actions: Actions;
  queryKey: readonly string[];
}

/**
 * The ledger with one person (domain rules, "Settle-ups"). Points have no cash value. Settle: pick open receipts
 * and settle them (you're down on the net; they can reject it for 48 hours) or mark them as settled (you're up, or
 * it's even; final). History: every settle, newest first.
 */
export function LedgerDialog(props: LedgerProps) {
  const { user, expire, group, queryKey } = props;
  const person = usePerson(group.otherUserId, user, expire, queryKey);
  const name = person?.name ?? "them";
  const net = group.netCents;
  const points = formatPoints(net < 0n ? -net : net);
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm" className="w-full">
          <BookOpen /> Point Ledger
        </Button>
      </DialogTrigger>
      <DialogContent className="flex max-h-[90dvh] max-w-2xl flex-col overflow-hidden p-4 sm:p-6">
        <DialogHeader className="pr-6 text-left">
          <DialogTitle className="font-display text-3xl font-black uppercase">Ledger</DialogTitle>
          <DialogDescription>
            {net < 0n ? (
              <>
                You're down <span className="font-mono font-semibold text-loss">{points} pts</span>{" "}
                with {name}
              </>
            ) : net > 0n ? (
              <>
                You're up <span className="font-mono font-semibold text-win">{points} pts</span>{" "}
                with {name}
              </>
            ) : (
              <>You're even with {name}</>
            )}
          </DialogDescription>
        </DialogHeader>
        <LedgerBody {...props} name={name} />
      </DialogContent>
    </Dialog>
  );
}

function LedgerBody(props: LedgerProps & { name: string }) {
  const { group, actions } = props;
  // A settle you can still reject is the thing to see first.
  const [tab, setTab] = useState<Tab>(
    group.pendingSettles.some((settle) => settle.canReject) ? "history" : "settle",
  );
  return (
    <div className="flex min-h-0 flex-col gap-3">
      {/* Also shown here: the dialog covers the copy on the page. */}
      <ActionStatus
        actions={actions}
        pendingText="A settle-up may not have been saved."
        checkText="Check the ledger first."
      />
      <SegmentedTabs<Tab>
        size="sm"
        ariaLabel="Ledger"
        tabs={[
          { id: "settle", label: "Settle" },
          { id: "history", label: "History" },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === "settle" ? <SettleTab {...props} /> : <HistoryTab {...props} />}
    </div>
  );
}

const ACTION_COPY: Record<SettleKind, { label: (amount: string) => string; confirm: string }> = {
  sent: {
    label: (amount) => `Settle ${amount}`,
    confirm:
      "It counts as settled right away. They have 48 hours to reject it, which reopens these receipts.",
  },
  received: {
    label: (amount) => (amount === "0.00" ? "Settle even" : `Mark ${amount} as settled`),
    confirm: "This closes these receipts for good.",
  },
};

function SettleTab({ user, group, actions, name }: LedgerProps & { name: string }) {
  const other = group.otherUserId;
  const review = group.pendingReviewRevision;
  const tableName = useTableNames(user.id);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  // Only receipts that are still open: a refresh drops any that closed meanwhile.
  const selection = group.lines.filter((line) => chosen.has(line.id));
  const net = selectionNet(selection, user.id);
  const kinds = selectionActions(selection, user.id);
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const allChosen = group.lines.length > 0 && selection.length === group.lines.length;
  const amount = formatPoints(net < 0n ? -net : net);

  function toggle(id: string) {
    setChosen((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function settle(kind: SettleKind) {
    actions.start({ kind: "settle", other, settle: kind, lineIds: selection.map((l) => l.id) });
    setChosen(new Set());
  }

  return (
    <>
      {review && (
        <div role="status" className="space-y-2 border-l-2 border-invite bg-invite/10 p-3 text-xs">
          <p className="flex gap-2">
            <TriangleAlert className="size-4 shrink-0 text-invite" />
            <span>
              Your balance with {name} changed after a new points settlement. Check what's open
              below before settling.
            </span>
          </p>
          <Button
            size="sm"
            variant="outline"
            disabled={blocked}
            onClick={() => actions.start({ kind: "review", other, revision: review })}
          >
            Got it
          </Button>
        </div>
      )}
      {group.lines.length === 0 ? (
        <EmptyState className="py-8">Nothing open with {name}.</EmptyState>
      ) : (
        <>
          <label className="flex items-center gap-3 border-b px-1 pb-2 text-xs font-semibold text-muted-foreground">
            <input
              type="checkbox"
              className="size-4 accent-primary"
              checked={allChosen}
              onChange={() =>
                setChosen(allChosen ? new Set() : new Set(group.lines.map((line) => line.id)))
              }
            />
            Select all ({group.lines.length})
          </label>
          <ScrollContainer isVisible={false} className="min-h-0 divide-y divide-border">
            {group.lines.map((line) => (
              <ReceiptChoice
                key={line.id}
                line={line}
                viewerId={user.id}
                table={tableName(line.tableId)}
                checked={chosen.has(line.id)}
                onToggle={() => toggle(line.id)}
              />
            ))}
          </ScrollContainer>
          <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3">
            <p className="text-lg font-bold" aria-live="polite">
              {selection.length === 0 ? (
                <span className="text-muted-foreground">Choose receipts to settle.</span>
              ) : net < 0n ? (
                <>
                  You're down <span className="font-mono text-loss">{amount}</span>
                </>
              ) : net > 0n ? (
                <>
                  You're up <span className="font-mono text-win">{amount}</span>
                </>
              ) : (
                "These even out"
              )}
            </p>
            <div className="flex flex-wrap gap-2">
              {kinds.map((kind) => (
                <ConfirmAction
                  key={kind}
                  label={ACTION_COPY[kind].label(amount)}
                  title={`${ACTION_COPY[kind].label(amount)}?`}
                  description={ACTION_COPY[kind].confirm}
                  disabled={blocked || !!review}
                  onConfirm={() => settle(kind)}
                />
              ))}
            </div>
          </div>
        </>
      )}
    </>
  );
}

function ReceiptChoice({
  line,
  viewerId,
  table,
  checked,
  onToggle,
}: {
  line: SettlementLine;
  viewerId: string;
  table: { emoji: string; name: string } | null;
  checked: boolean;
  onToggle: () => void;
}) {
  const iPay = line.payerId === viewerId;
  return (
    <label className="flex cursor-pointer items-center gap-3 px-1 py-2.5 text-sm">
      <input
        type="checkbox"
        className="size-4 accent-primary"
        checked={checked}
        onChange={onToggle}
      />
      <span className="min-w-0 flex-1">
        <span className="block truncate font-semibold">
          {table ? `${table.emoji} ${table.name}` : "A table you've left"}
        </span>
        <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
          {dateTimeLabel(line.createdAt)}
        </span>
      </span>
      <span className={cn("scoreboard shrink-0 text-lg", iPay ? "text-loss" : "text-win")}>
        {iPay ? "−" : "+"}
        {formatPoints(line.amountCents)}
      </span>
    </label>
  );
}

function HistoryTab({
  user,
  expire,
  group,
  actions,
  queryKey,
  name,
}: LedgerProps & { name: string }) {
  const other = group.otherUserId;
  const history = useInfiniteQuery({
    queryKey: [...queryKey, "settles", other],
    queryFn: ({ pageParam, signal }) => listSettles(user.id, other, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  useExpireOnUnauthorized([history.error], expire);
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const settles = history.data?.pages.flatMap((page) => page.items) ?? [];
  if (history.isPending) return <LoadingList label="Loading history" rows={3} />;
  if (history.isError) return <p role="alert">{backendErrorMessage(history.error)}</p>;
  if (settles.length === 0)
    return <EmptyState className="py-8">No settle-ups with {name} yet.</EmptyState>;
  return (
    <ScrollContainer isVisible={false} className="min-h-0 divide-y divide-border">
      <AnimatedList>
        {settles.map((settle) => (
          <AnimatedItem key={settle.id} layout={false} className="py-3">
            <SettleRow
              settle={settle}
              viewerId={user.id}
              name={name}
              blocked={blocked}
              onReject={() => actions.start({ kind: "reject", other, settleId: settle.id })}
            />
          </AnimatedItem>
        ))}
      </AnimatedList>
      {history.hasNextPage && (
        <LoadMore
          loading={history.isFetchingNextPage}
          onLoad={() => void history.fetchNextPage()}
        />
      )}
    </ScrollContainer>
  );
}

/** "You settled 4.00" / "alex marked 2.00 as settled", with when and whether it can be rejected. */
function settleSentence(settle: Settle, viewerId: string, name: string): string {
  const who = settle.senderId === viewerId ? "You" : name;
  const amount = formatPoints(settle.amountCents);
  switch (settle.kind) {
    case "sent":
      return `${who} settled ${amount}`;
    case "received":
      return settle.amountCents === 0n
        ? `${who} settled even`
        : `${who} marked ${amount} as settled`;
  }
}

function SettleRow({
  settle,
  viewerId,
  name,
  blocked,
  onReject,
}: {
  settle: Settle;
  viewerId: string;
  name: string;
  blocked: boolean;
  onReject: () => void;
}) {
  const receiver = settle.otherId === viewerId ? "You" : name;
  const count = settle.lines.length;
  return (
    <div className={cn("flex flex-wrap items-center gap-3", settle.rejectedAt && "opacity-65")}>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold">{settleSentence(settle, viewerId, name)}</p>
        <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
          {dateTimeLabel(settle.createdAt)} · {count} {count === 1 ? "receipt" : "receipts"}
          {settle.rejectedAt
            ? ` · Rejected by ${receiver === "You" ? "you" : receiver}`
            : settle.rejectBy && Date.parse(settle.rejectBy) > Date.now()
              ? ` · Can be rejected for ${hoursLeft(settle.rejectBy, Date.now())}h`
              : ""}
        </p>
      </div>
      {settle.canReject && (
        <ConfirmAction
          label="Reject"
          title="Reject this settle-up?"
          description={`Only if you don't agree these are settled. Its receipts reopen, and ${name} can settle again.`}
          disabled={blocked}
          onConfirm={onReject}
        />
      )}
    </div>
  );
}

/** A signed, colored points amount: red when you're down, green when you're up. */
export function Net({ cents, className }: { cents: bigint; className?: string }) {
  return (
    <span
      className={cn(
        "font-mono font-semibold",
        cents < 0n ? "text-loss" : cents > 0n ? "text-win" : "text-muted-foreground",
        className,
      )}
    >
      {cents < 0n ? "−" : cents > 0n ? "+" : ""}
      {formatPoints(cents < 0n ? -cents : cents)} pts
    </span>
  );
}

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  Check,
  CheckCheck,
  Clock3,
  HeartHandshake,
  ReceiptText,
  Send,
  TriangleAlert,
} from "lucide-react";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ConfirmAction } from "@/components/confirm-action";
import { ScrollContainer } from "@/components/scroll-container";
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
import { PersonAvatar, PersonName, type User, usePerson } from "@/features/people";
import { type TableName, useTableNames } from "@/features/tables";
import type { SettlementIntent, useSettlementActions } from "./actions";
import {
  formatPoints,
  receiptHistory,
  type PendingReview,
  type SettlementGroup,
  type SettlementLine,
} from "./api";
import {
  groupOptions,
  isClosed,
  lineOptions,
  needsReview,
  type ActionOption,
} from "./receipt-rules";

export type Actions = ReturnType<typeof useSettlementActions>;

/** Receipts per screenful; older ones load on request. */
const PAGE = 30;

export interface ReceiptsProps {
  user: User;
  expire: () => void;
  other: string;
  /** Open receipts and their net; absent when totals overflow or only a warning remains. */
  group?: SettlementGroup;
  /** An unreviewed balance-change warning with this person. */
  review?: PendingReview;
  /** True until the review state is known to be clear; blocks sending and asking. */
  reviewRequired: boolean;
  actions: Actions;
  queryKey: readonly string[];
}

/**
 * "Settle-up receipts" with one person: any balance-change warning to review, actions on every open receipt at
 * once, then each dated receipt with who sent and who received, and the viewer's actions on it.
 */
export function ReceiptsDialog(props: ReceiptsProps) {
  const { user, expire, other, group, queryKey } = props;
  const person = usePerson(other, user, expire, queryKey);
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm" className="w-full">
          <ReceiptText /> Settle-up receipts
        </Button>
      </DialogTrigger>
      <DialogContent className="flex max-h-[90dvh] max-w-2xl flex-col overflow-hidden p-4 sm:p-6">
        <DialogHeader className="pr-6 text-left">
          <DialogTitle>Settle-up with {person?.name ?? "this person"}</DialogTitle>
          <DialogDescription>
            {group ? (
              <>
                Outstanding net <Net cents={group.netCents} />
              </>
            ) : (
              "These actions track payments between players; they don't move money."
            )}
          </DialogDescription>
        </DialogHeader>
        <ReceiptsBody {...props} />
      </DialogContent>
    </Dialog>
  );
}

function ReceiptsBody({
  user,
  expire,
  other,
  group,
  review,
  reviewRequired,
  actions,
  queryKey,
}: ReceiptsProps) {
  const [visible, setVisible] = useState(PAGE);
  const tableName = useTableNames(user.id);
  // Mounted only while the dialog is open, so history loads on demand.
  const history = useQuery({
    queryKey: [...queryKey, "history", other],
    queryFn: ({ signal }) => receiptHistory(user.id, other, signal),
    retry: false,
  });
  useExpireOnUnauthorized([history.error], expire);
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  const rows = [...(history.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  return (
    <div className="flex min-h-0 flex-col gap-3">
      {/* Also shown here: the dialog covers the copy on the page. */}
      <PendingAction actions={actions} />
      {review && (
        <div
          role="status"
          className="space-y-2 border-l-2 border-status-pending bg-status-pending/10 p-3 text-xs"
        >
          <p className="flex gap-2">
            <TriangleAlert className="size-4 shrink-0 text-status-pending" />
            <span>
              Balance changed with{" "}
              <PersonName id={other} user={user} expire={expire} queryKey={queryKey} />. A pending
              payment or forgiveness request was reset. Check any money already sent before sending
              again.
            </span>
          </p>
          <Button
            size="sm"
            variant="outline"
            disabled={disabled}
            onClick={() =>
              actions.start({ kind: "review", other, revision: review.pendingReviewRevision })
            }
          >
            I've reviewed this
          </Button>
        </div>
      )}
      {reviewRequired && !review && (
        <p className="text-xs text-muted-foreground">
          Review status must be current before sending or requesting forgiveness.
        </p>
      )}
      {group && (
        <Options
          options={groupOptions(group, user.id)}
          actions={actions}
          blocked={(action) => disabled || (reviewRequired && needsReview(action))}
          intent={(action) => ({ kind: "group", other, action, revision: group.revision })}
          description="This updates every open receipt with them. Verify the balance before continuing."
        />
      )}
      <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_4.5rem] gap-2 border-b pb-2 text-center text-xs font-semibold sm:grid-cols-[minmax(0,1fr)_6rem_6rem]">
        <span className="text-left text-muted-foreground">Table / amount</span>
        <span className="flex items-center justify-center gap-1">
          <Send className="size-3" /> Sent
        </span>
        <span className="flex items-center justify-center gap-1">
          <CheckCheck className="size-3" /> Received
        </span>
      </div>
      {history.isPending ? (
        <p className="text-sm text-muted-foreground">Loading receipts…</p>
      ) : history.isError ? (
        <div className="space-y-2">
          <p role="alert">{backendErrorMessage(history.error)}</p>
          <Button size="sm" variant="outline" onClick={() => void history.refetch()}>
            Retry history
          </Button>
        </div>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No receipts with them yet.</p>
      ) : (
        <ScrollContainer isVisible={false} className="min-h-0 divide-y divide-border">
          <AnimatedList>
            {rows.slice(0, visible).map((line) => (
              <AnimatedItem
                key={line.id}
                layout={false}
                className={cn("py-3", isClosed(line) && "opacity-65")}
              >
                <ReceiptRow
                  line={line}
                  table={tableName(line.tableId)}
                  user={user}
                  expire={expire}
                  queryKey={queryKey}
                />
                <Options
                  options={lineOptions(line, user.id)}
                  actions={actions}
                  blocked={(action) => disabled || (reviewRequired && needsReview(action))}
                  intent={(action) => ({
                    kind: "line",
                    other,
                    lineId: line.id,
                    action,
                    revision: line.revision,
                  })}
                  description={`This closes the receipt for ${formatPoints(line.amountCents)} points. Verify before continuing.`}
                  className="mt-2"
                />
              </AnimatedItem>
            ))}
          </AnimatedList>
          {visible < rows.length && (
            <Button
              variant="ghost"
              size="sm"
              className="mt-2 w-full"
              onClick={() => setVisible((n) => n + PAGE)}
            >
              Older receipts
            </Button>
          )}
        </ScrollContainer>
      )}
    </div>
  );
}

function ReceiptRow({
  line,
  table,
  user,
  expire,
  queryKey,
}: {
  line: SettlementLine;
  table: TableName | null;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
}) {
  const payerIsMe = line.payerId === user.id;
  const sent = line.status === "sent" || line.status === "paid";
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_4.5rem_4.5rem] items-start gap-2 sm:grid-cols-[minmax(0,1fr)_6rem_6rem]">
      <div className="min-w-0 space-y-1">
        {table?.current ? (
          <Link
            to="/tables/$tableId"
            params={{ tableId: line.tableId }}
            className="block break-words text-sm font-semibold hover:underline"
          >
            {table.emoji} {table.name}
          </Link>
        ) : (
          <p className="break-words text-sm font-semibold">
            {table ? `${table.emoji} ${table.name} (deleted)` : "A table you've left"}
          </p>
        )}
        <p className="text-[11px] text-muted-foreground">{dateTimeLabel(line.createdAt)}</p>
        <p className={cn("font-mono font-bold", payerIsMe ? "text-loss" : "text-win")}>
          {payerIsMe ? "−" : "+"}
          {formatPoints(line.amountCents)}
        </p>
        <p className="text-[11px] text-muted-foreground">
          {line.status === "forgiven"
            ? "Forgiven"
            : line.status === "paid"
              ? "Completed"
              : line.status === "disputed"
                ? "Not received; send again"
                : line.status === "forgive_asked"
                  ? "Forgiveness asked"
                  : payerIsMe
                    ? "You pay them"
                    : "They pay you"}
        </p>
      </div>
      {[line.payerId, line.payeeId].map((id, column) => (
        <div
          key={`${id}:${column}`}
          className="flex min-w-0 flex-col items-center gap-1 text-center"
        >
          <PersonAvatar id={id} size="xs" user={user} expire={expire} queryKey={queryKey} />
          <span className="w-full truncate text-[10px]">
            <PersonName id={id} user={user} expire={expire} queryKey={queryKey} />
          </span>
          {line.status === "forgiven" ? (
            <HeartHandshake aria-label="Forgiven" className="size-5 text-muted-foreground" />
          ) : (column === 0 ? sent : line.status === "paid") ? (
            <Check aria-label={column === 0 ? "Sent" : "Received"} className="size-5 text-win" />
          ) : (
            <Clock3 aria-label="Waiting" className="size-4 text-muted-foreground" />
          )}
        </div>
      ))}
    </div>
  );
}

function Options<A extends string>({
  options,
  actions,
  blocked,
  intent,
  description,
  className,
}: {
  options: ActionOption<A>[];
  actions: Actions;
  blocked: (action: A) => boolean;
  intent: (action: A) => SettlementIntent;
  description: string;
  className?: string;
}) {
  if (options.length === 0) return null;
  return (
    <div className={cn("flex flex-wrap gap-2", className)}>
      {options.map(({ action, label, confirm }) =>
        confirm ? (
          <ConfirmAction
            key={action}
            label={label}
            title={`${label}?`}
            description={description}
            disabled={blocked(action)}
            onConfirm={() => actions.start(intent(action))}
          />
        ) : (
          <Button
            key={action}
            size="sm"
            variant="outline"
            disabled={blocked(action)}
            onClick={() => actions.start(intent(action))}
          >
            {label}
          </Button>
        ),
      )}
    </div>
  );
}

/** A signed, colored points amount: red when you owe, green when you're owed. */
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

/** An action whose outcome is unknown blocks the rest until it's retried with the same key or cleared. */
export function PendingAction({ actions }: { actions: Actions }) {
  return (
    <>
      {actions.storageError && <p role="alert">{actions.storageError} Actions are disabled.</p>}
      {actions.message && <p role="alert">{actions.message}</p>}
      {actions.pending && (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <p>One settlement action has an unknown outcome. Retry it before making other changes.</p>
          <Button
            size="sm"
            disabled={actions.busy || !!actions.storageError}
            onClick={actions.retry}
          >
            Retry same action
          </Button>{" "}
          <ConfirmAction
            label="Clear saved action"
            title="Clear the saved action?"
            description="Only clear it after checking the receipt history. The original action may have succeeded."
            disabled={actions.busy || !!actions.storageError}
            onConfirm={actions.clearAfterReview}
          />
        </div>
      )}
    </>
  );
}

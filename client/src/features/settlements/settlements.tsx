import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ActionStatus } from "@/components/action-status";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { settlementsFeed } from "@/lib/realtime-connection";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { cn } from "@/lib/utils";
import { PersonAvatar, PersonName, type User } from "@/features/people";
import { useSettlementActions } from "./actions";
import { listSettlementGroups, type SettlementGroup } from "./api";
import { groupSummary } from "./receipt-rules";
import { Net, ReceiptsDialog, type Actions } from "./receipts-dialog";
import { LoadingList } from "@/components/loading";

/**
 * Settle-ups on the Friends page: a sideways-scrolling card per person you've had receipts with, showing the
 * open net between you. Each opens their receipts. One request: each group carries its receipts, any unreviewed
 * balance-change warning, and the viewer's available actions.
 */
export function SettlementReceipts({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, "settlements");
  useRealtimeFeed(settlementsFeed, () => void queryClient.invalidateQueries({ queryKey: key }));
  const groups = useQuery({
    queryKey: [...key, "groups"],
    queryFn: ({ signal }) => listSettlementGroups(user.id, signal),
    retry: false,
  });
  useExpireOnUnauthorized([groups.error], expire);
  const actions = useSettlementActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: key });
    },
    expire,
  );

  return (
    <section className="space-y-4" aria-labelledby="settlements-heading">
      {/* No refresh button: the settlements feed reloads this on every receipt change and on reconnect. */}
      <h2
        id="settlements-heading"
        className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
      >
        Settle-ups
      </h2>
      <p className="text-sm text-muted-foreground">Net of your open settle-ups with each person.</p>
      <ActionStatus
        actions={actions}
        pendingText="A settlement action may not have been saved."
        checkText="Check the receipt history first."
      />
      {groups.isPending ? (
        <LoadingList label="Loading settle-ups" rows={3} rowClassName="h-20" />
      ) : groups.isError ? (
        <p role="alert">{backendErrorMessage(groups.error)}</p>
      ) : groups.data.length === 0 ? (
        <EmptyState boxed className="p-6">
          No settle-ups. Points settled at a table show up here.
        </EmptyState>
      ) : (
        <ScrollContainer
          isHorizontal
          isVisible={false}
          aria-label="Settle-ups by person"
          className="snap-x gap-3 pb-2 md:scrollbar-thin"
        >
          <AnimatedList>
            {groups.data.map((group) => (
              <AnimatedItem key={group.otherUserId} isHorizontal className="shrink-0 snap-start">
                <SettleCard
                  group={group}
                  user={user}
                  expire={expire}
                  queryKey={key}
                  actions={actions}
                />
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ScrollContainer>
      )}
    </section>
  );
}

/** A person's net with you; actions live on the dated receipts and group actions in the popup. */
function SettleCard({
  group,
  user,
  expire,
  queryKey,
  actions,
}: {
  group: SettlementGroup;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
  actions: Actions;
}) {
  const other = group.otherUserId;
  const net = group.netCents;
  const summary = groupSummary(group.lines);
  return (
    <div
      className={cn(
        "flex min-h-48 w-60 flex-col gap-2 rounded-lg border p-3",
        net < 0n
          ? "border-loss/50 bg-loss/10"
          : net > 0n
            ? "border-primary/40 bg-primary/5"
            : "bg-surface",
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <PersonAvatar id={other} size="md" user={user} expire={expire} queryKey={queryKey} />
        <div className="min-w-0 flex-1">
          <p className="break-words font-semibold leading-tight">
            <PersonName id={other} user={user} expire={expire} queryKey={queryKey} />
          </p>
          <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            {net < 0n ? "You pay them" : net > 0n ? "They pay you" : "You're square"}
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {summary.tables} {summary.tables === 1 ? "table" : "tables"} · {summary.open} open
          </p>
        </div>
      </div>
      <div className="flex items-baseline justify-between gap-2">
        <Net cents={net} className="scoreboard text-3xl leading-none" />
      </div>
      {group.pendingReviewRevision && (
        <p className="text-[11px] font-semibold text-status-pending">Balance changed: review</p>
      )}
      <div className="mt-auto">
        <ReceiptsDialog
          user={user}
          expire={expire}
          group={group}
          actions={actions}
          queryKey={queryKey}
        />
      </div>
    </div>
  );
}

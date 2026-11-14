import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { Button } from "@/components/ui/button";
import { BackendError, backendErrorMessage } from "@/lib/api-client";
import { useRealtimeFeed } from "@/lib/realtime";
import { settlementsFeed } from "@/lib/realtime-connection";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { cn } from "@/lib/utils";
import { PersonAvatar, PersonName, type User } from "@/features/people";
import { useSettlementActions } from "./actions";
import {
  listPendingReviews,
  listSettlementCounterparties,
  listSettlementGroups,
  type PendingReview,
  type SettlementGroup,
} from "./api";
import { groupSummary } from "./receipt-rules";
import { Net, PendingAction, ReceiptsDialog, type Actions } from "./receipts-dialog";

/** One person you have settle-up receipts or an unreviewed warning with. */
interface Counterparty {
  other: string;
  group?: SettlementGroup;
  review?: PendingReview;
}

/**
 * Settle-ups on the Friends page: a card per person showing the open net between you, sideways-scrolling. Each
 * opens their receipts. A balance-change warning also gets a card, so it stays reachable after the receipts
 * close. If a group's total overflows, cards list each person without a net and the receipts still work.
 */
export function SettlementReceipts({ user, expire }: { user: User; expire: () => void }) {
  const queryClient = useQueryClient();
  const key = ["backend-tables", user.id, "settlements"] as const;
  useRealtimeFeed(settlementsFeed, () => void queryClient.invalidateQueries({ queryKey: key }));
  const reviews = useQuery({
    queryKey: [...key, "reviews"],
    queryFn: ({ signal }) => listPendingReviews(user.id, signal),
    retry: false,
  });
  const groups = useQuery({
    queryKey: [...key, "groups"],
    queryFn: ({ signal }) => listSettlementGroups(user.id, signal),
    retry: false,
  });
  const overflow =
    groups.isError &&
    groups.error instanceof BackendError &&
    groups.error.code === "group_net_out_of_range";
  const counterparties = useQuery({
    queryKey: [...key, "counterparties"],
    queryFn: ({ signal }) => listSettlementCounterparties(user.id, signal),
    enabled: overflow,
    retry: false,
  });
  useExpireOnUnauthorized([reviews.error, groups.error, counterparties.error], expire);
  const actions = useSettlementActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: key });
    },
    expire,
  );

  const people = new Map<string, Counterparty>();
  for (const group of groups.data ?? [])
    people.set(group.otherUserId, { other: group.otherUserId, group });
  if (overflow) for (const other of counterparties.data ?? []) people.set(other, { other });
  for (const review of reviews.data ?? [])
    people.set(review.otherUserId, {
      ...people.get(review.otherUserId),
      other: review.otherUserId,
      review,
    });
  const reviewRequired = (person: Counterparty) =>
    reviews.isPending ||
    reviews.isError ||
    reviews.isFetching ||
    !!person.review ||
    (person.group?.pendingReviewRevision ?? null) !== null;
  const loading = groups.isPending || reviews.isPending || (overflow && counterparties.isPending);

  return (
    <section className="space-y-4" aria-labelledby="settlements-heading">
      <div className="flex items-center justify-between gap-3">
        <h2
          id="settlements-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Settle-ups
        </h2>
        <Button
          variant="outline"
          size="sm"
          disabled={reviews.isFetching || groups.isFetching}
          onClick={() => void queryClient.invalidateQueries({ queryKey: key })}
        >
          Refresh receipts
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">
        Net of your open settle-ups with each person. These actions track payments between players;
        they don't move money.
      </p>
      <PendingAction actions={actions} />
      {reviews.isError && (
        <p role="alert">
          Cannot check balance-change reviews: {backendErrorMessage(reviews.error)} Sending and
          forgiveness requests are disabled.
        </p>
      )}
      {groups.isError && (
        <p role="alert">
          {overflow
            ? "A receipt group total is outside the supported range. Group actions are unavailable; each person's receipts still work."
            : backendErrorMessage(groups.error)}
        </p>
      )}
      {overflow && counterparties.isError && (
        <p role="alert">{backendErrorMessage(counterparties.error)}</p>
      )}
      {loading ? (
        <p className="text-sm text-muted-foreground">Loading receipts…</p>
      ) : people.size === 0 ? (
        !groups.isError && (
          <EmptyState boxed className="p-6">
            No settle-ups. Points settled at a table show up here.
          </EmptyState>
        )
      ) : (
        <ScrollContainer
          isHorizontal
          isVisible={false}
          aria-label="Settle-ups by person"
          className="snap-x gap-3 pb-2 md:scrollbar-thin"
        >
          <AnimatedList>
            {[...people.values()].map((person) => (
              <AnimatedItem key={person.other} isHorizontal className="shrink-0 snap-start">
                <SettleCard
                  person={person}
                  user={user}
                  expire={expire}
                  queryKey={key}
                  actions={actions}
                  reviewRequired={reviewRequired(person)}
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
  person,
  user,
  expire,
  queryKey,
  actions,
  reviewRequired,
}: {
  person: Counterparty;
  user: User;
  expire: () => void;
  queryKey: readonly string[];
  actions: Actions;
  reviewRequired: boolean;
}) {
  const { other, group, review } = person;
  const net = group?.netCents;
  const summary = groupSummary(group?.lines ?? []);
  const owe = net !== undefined && net < 0n;
  const owed = net !== undefined && net > 0n;
  return (
    <div
      className={cn(
        "flex min-h-48 w-60 flex-col gap-2 rounded-lg border p-3",
        owe ? "border-loss/50 bg-loss/10" : owed ? "border-primary/40 bg-primary/5" : "bg-surface",
      )}
    >
      <div className="flex min-w-0 items-center gap-3">
        <PersonAvatar id={other} size="md" user={user} expire={expire} queryKey={queryKey} />
        <div className="min-w-0 flex-1">
          <p className="break-words font-semibold leading-tight">
            <PersonName id={other} user={user} expire={expire} queryKey={queryKey} />
          </p>
          <p className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
            {net === undefined
              ? "Total unavailable"
              : owe
                ? "You pay them"
                : owed
                  ? "They pay you"
                  : "You're square"}
          </p>
          {group && (
            <p className="mt-1 text-xs text-muted-foreground">
              {summary.tables} {summary.tables === 1 ? "table" : "tables"} · {summary.open} open
            </p>
          )}
        </div>
      </div>
      <div className="flex items-baseline justify-between gap-2">
        {net !== undefined && <Net cents={net} className="scoreboard text-3xl leading-none" />}
        {summary.awaitingReceipt && (
          <span className="text-[10px] text-status-pending">Awaiting receipt</span>
        )}
      </div>
      {review && (
        <p className="text-[11px] font-semibold text-status-pending">Balance changed: review</p>
      )}
      <div className="mt-auto">
        <ReceiptsDialog
          user={user}
          expire={expire}
          other={other}
          {...(group ? { group } : {})}
          {...(review ? { review } : {})}
          reviewRequired={reviewRequired}
          actions={actions}
          queryKey={queryKey}
        />
      </div>
    </div>
  );
}

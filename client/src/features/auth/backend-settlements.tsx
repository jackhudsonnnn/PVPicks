import { useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { Button } from "@/components/ui/button";
import { BackendError, backendErrorMessage, type BackendUser } from "./backend-api";
import { ConfirmAction } from "./backend-confirm-action";
import { PersonName } from "./backend-person-name";
import { useSettlementActions, type SettlementIntent } from "./settlement-actions";
import {
  listPendingReviews,
  listSettlementCounterparties,
  listSettlementGroups,
  receiptHistory,
  type GroupAction,
  type SettlementGroup,
  type SettlementLine,
} from "./settlement-api";
import { useRealtimeFeed } from "./realtime";
import { settlementsFeed } from "./realtime-connection";

type Actions = ReturnType<typeof useSettlementActions>;

export function BackendSettlements({ user, expire }: { user: BackendUser; expire: () => void }) {
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
  const unauthorized = [reviews.error, groups.error, counterparties.error].some(
    (error) => error instanceof BackendError && error.status === 401,
  );
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  const actions = useSettlementActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: key });
    },
    expire,
  );
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;

  return (
    <section className="space-y-4" aria-labelledby="settlements-heading">
      <div className="flex items-center justify-between gap-3">
        <h2 id="settlements-heading" className="font-display text-2xl font-bold uppercase">
          Settle-up receipts
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
        These actions track payments between players; they do not transfer money.
      </p>
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
      {reviews.isPending ? (
        <p>Loading balance-change reviews…</p>
      ) : reviews.isError ? (
        <p role="alert">
          Cannot check balance-change reviews: {backendErrorMessage(reviews.error)} Sending and
          forgiveness requests are disabled.
        </p>
      ) : (
        <ul className="space-y-3">
          <AnimatedList>
            {reviews.data.map((review) => (
              <AnimatedItem
                key={review.otherUserId}
                as="li"
                className="rounded-lg border bg-surface p-3"
              >
                <p role="status">
                  Balance changed with{" "}
                  <PersonName id={review.otherUserId} user={user} expire={expire} queryKey={key} />.
                  A pending payment or forgiveness request was reset. Check any money already sent
                  before sending again.
                </p>
                <Button
                  size="sm"
                  variant="outline"
                  className="mt-2"
                  disabled={disabled || reviews.isFetching}
                  onClick={() =>
                    actions.start({
                      kind: "review",
                      other: review.otherUserId,
                      revision: review.pendingReviewRevision,
                    })
                  }
                >
                  I've reviewed this
                </Button>
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ul>
      )}
      {groups.isPending ? (
        <p>Loading receipts…</p>
      ) : groups.isError ? (
        <p role="alert">
          {overflow
            ? "A receipt group total is outside the supported range. Group actions are unavailable; individual receipts are listed below."
            : backendErrorMessage(groups.error)}
        </p>
      ) : groups.data.length === 0 ? (
        <p>No settle-up receipts yet.</p>
      ) : (
        <ul className="space-y-3">
          <AnimatedList>
            {groups.data.map((group) => (
              <AnimatedItem
                key={group.otherUserId}
                as="li"
                className="rounded-lg border bg-surface p-4"
              >
                <Group
                  group={group}
                  user={user}
                  expire={expire}
                  queryKey={key}
                  actions={actions}
                  reviewRequired={
                    reviews.isPending ||
                    reviews.isError ||
                    reviews.isFetching ||
                    group.pendingReviewRevision !== null ||
                    reviews.data.some((review) => review.otherUserId === group.otherUserId)
                  }
                />
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ul>
      )}
      {overflow &&
        (counterparties.isPending ? (
          <p>Loading receipt counterparties…</p>
        ) : counterparties.isError ? (
          <p role="alert">{backendErrorMessage(counterparties.error)}</p>
        ) : (
          <ul className="space-y-3">
            <AnimatedList>
              {counterparties.data.map((other) => (
                <AnimatedItem key={other} as="li" className="rounded-lg border bg-surface p-4">
                  <p className="font-semibold">
                    <PersonName id={other} user={user} expire={expire} queryKey={key} />
                  </p>
                  <ReceiptHistory
                    user={user}
                    other={other}
                    expire={expire}
                    queryKey={key}
                    actions={actions}
                    reviewRequired={
                      reviews.isPending ||
                      reviews.isError ||
                      reviews.isFetching ||
                      reviews.data.some((review) => review.otherUserId === other)
                    }
                  />
                </AnimatedItem>
              ))}
            </AnimatedList>
          </ul>
        ))}
    </section>
  );
}

function points(cents: bigint) {
  const abs = cents < 0n ? -cents : cents;
  return `${cents < 0n ? "-" : ""}${(abs / 100n).toLocaleString()}.${(abs % 100n).toString().padStart(2, "0")}`;
}

function Group({
  group,
  user,
  expire,
  queryKey,
  actions,
  reviewRequired,
}: {
  group: SettlementGroup;
  user: BackendUser;
  expire: () => void;
  queryKey: readonly string[];
  actions: Actions;
  reviewRequired: boolean;
}) {
  const open = group.lines.filter((line) => line.status !== "paid" && line.status !== "forgiven");
  const payer = group.netCents < 0n ? user.id : group.otherUserId;
  const directional = open.filter((line) => line.payerId === payer);
  const sent = directional.some((line) => line.status === "sent");
  const asked = directional.some(
    (line) => line.status === "forgive_asked" || line.forgiveRequestedAt,
  );
  const declined = directional.some(
    (line) =>
      line.forgiveDeclinedAt && Date.now() - new Date(line.forgiveDeclinedAt).getTime() < 86400000,
  );
  const options: { action: GroupAction; label: string; confirm?: boolean }[] = [];
  if (open.length && group.netCents === 0n)
    options.push({ action: "paid", label: "Confirm zero net", confirm: true });
  else if (open.length && payer === user.id) {
    if (!sent) options.push({ action: "sent", label: "Mark group sent" });
    if (!sent && !asked && !declined)
      options.push({ action: "ask", label: "Ask to forgive group" });
  } else if (open.length) {
    options.push({ action: "paid", label: "Confirm group received", confirm: true });
    if (!sent) options.push({ action: "forgiven", label: "Forgive group", confirm: true });
    if (sent) options.push({ action: "unpaid", label: "Group not received" });
    if (!sent && asked) options.push({ action: "decline", label: "Decline group forgiveness" });
  }
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  return (
    <div className="space-y-3">
      <h3 className="font-semibold">
        <PersonName id={group.otherUserId} user={user} expire={expire} queryKey={queryKey} />
      </h3>
      <p>
        Open net: {points(group.netCents)} points{" "}
        {group.netCents < 0n ? "(you owe)" : group.netCents > 0n ? "(owed to you)" : ""}
      </p>
      {reviewRequired && (
        <p className="text-sm">
          Review status must be current before sending or requesting forgiveness.
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {options.map(({ action, label, confirm }) => {
          const blocked = disabled || (reviewRequired && (action === "sent" || action === "ask"));
          const intent: SettlementIntent = {
            kind: "group",
            other: group.otherUserId,
            action,
            revision: group.revision,
          };
          return confirm ? (
            <ConfirmAction
              key={action}
              label={label}
              title={`${label}?`}
              description="This updates every open receipt in the group. Verify the balance before continuing."
              disabled={blocked}
              onConfirm={() => actions.start(intent)}
            />
          ) : (
            <Button
              key={action}
              size="sm"
              variant="outline"
              disabled={blocked}
              onClick={() => actions.start(intent)}
            >
              {label}
            </Button>
          );
        })}
      </div>
      <details>
        <summary className="cursor-pointer text-sm font-semibold">Receipt history</summary>
        <ReceiptLines
          lines={group.lines}
          user={user}
          other={group.otherUserId}
          actions={actions}
          reviewRequired={reviewRequired}
        />
      </details>
    </div>
  );
}

function ReceiptHistory({
  user,
  other,
  expire,
  queryKey,
  actions,
  reviewRequired,
}: {
  user: BackendUser;
  other: string;
  expire: () => void;
  queryKey: readonly string[];
  actions: Actions;
  reviewRequired: boolean;
}) {
  const query = useQuery({
    queryKey: [...queryKey, "history", other],
    queryFn: ({ signal }) => receiptHistory(user.id, other, signal),
    retry: false,
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  return (
    <details className="mt-3">
      <summary className="cursor-pointer text-sm font-semibold">Receipt history</summary>
      {query.isPending ? (
        <p>Loading receipts…</p>
      ) : query.isError ? (
        <div>
          <p role="alert">{backendErrorMessage(query.error)}</p>
          <Button size="sm" variant="outline" onClick={() => void query.refetch()}>
            Retry history
          </Button>
        </div>
      ) : (
        <ReceiptLines
          lines={query.data}
          user={user}
          other={other}
          actions={actions}
          reviewRequired={reviewRequired}
        />
      )}
    </details>
  );
}

function ReceiptLines({
  lines,
  user,
  other,
  actions,
  reviewRequired,
}: {
  lines: SettlementLine[];
  user: BackendUser;
  other: string;
  actions: Actions;
  reviewRequired: boolean;
}) {
  return (
    <ul className="mt-3 space-y-3">
      <AnimatedList>
        {lines.map((line) => (
          <AnimatedItem as="li" key={line.id} className="space-y-2 border-t pt-3 text-sm">
            <p>
              {line.payerId === user.id ? "You owe" : "Owed to you"} {points(line.amountCents)}{" "}
              points · {line.status.replaceAll("_", " ")}
            </p>
            <p className="break-all text-muted-foreground">
              Table {line.tableId} · {new Date(line.createdAt).toLocaleString()}
            </p>
            <LineActions
              line={line}
              user={user}
              other={other}
              actions={actions}
              reviewRequired={reviewRequired}
            />
          </AnimatedItem>
        ))}
      </AnimatedList>
    </ul>
  );
}

function LineActions({
  line,
  user,
  other,
  actions,
  reviewRequired,
}: {
  line: SettlementLine;
  user: BackendUser;
  other: string;
  actions: Actions;
  reviewRequired: boolean;
}) {
  const unpaid = ["unpaid", "disputed", "forgive_asked"].includes(line.status);
  const asked = line.status === "forgive_asked" || !!line.forgiveRequestedAt;
  const cooldown =
    !!line.forgiveDeclinedAt && Date.now() - new Date(line.forgiveDeclinedAt).getTime() < 86400000;
  const options: {
    action: "sent" | "confirm" | "dispute" | "forgive" | "ask-forgive" | "decline-forgive";
    label: string;
    confirm?: boolean;
  }[] = [];
  if (line.payerId === user.id && unpaid) {
    options.push({ action: "sent", label: "I sent it" });
    if (!asked && !cooldown) options.push({ action: "ask-forgive", label: "Ask to forgive" });
  } else if (line.payeeId === user.id) {
    if (unpaid || line.status === "sent")
      options.push({ action: "confirm", label: "I received it", confirm: true });
    if (unpaid) options.push({ action: "forgive", label: "Forgive", confirm: true });
    if (unpaid && asked) options.push({ action: "decline-forgive", label: "Decline forgiveness" });
    if (line.status === "sent") options.push({ action: "dispute", label: "Didn't receive it" });
  }
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  return (
    <div className="flex flex-wrap gap-2">
      {options.map(({ action, label, confirm }) => {
        const blocked =
          disabled || (reviewRequired && (action === "sent" || action === "ask-forgive"));
        const intent: SettlementIntent = {
          kind: "line",
          other,
          lineId: line.id,
          action,
          revision: line.revision,
        };
        return confirm ? (
          <ConfirmAction
            key={action}
            label={label}
            title={`${label}?`}
            description={`This closes the receipt for ${points(line.amountCents)} points. Verify before continuing.`}
            disabled={blocked}
            onConfirm={() => actions.start(intent)}
          />
        ) : (
          <Button
            key={action}
            size="sm"
            variant="outline"
            disabled={blocked}
            onClick={() => actions.start(intent)}
          >
            {label}
          </Button>
        );
      })}
    </div>
  );
}

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Ban, Flag } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { StatusBadge } from "@/features/bets/components/status-badge";
import { MODES } from "@/features/bets/modes";
import { formatCents } from "@/lib/money";
import { dateTimeLabel } from "@/lib/time";
import { cn } from "@/lib/utils";
import {
  BackendError,
  backendErrorMessage,
  type BackendBet,
  type BackendTable,
  type BackendUser,
} from "./backend-api";
import { PersonAvatar } from "./backend-avatar";
import { BlockDialog, ReportDialog, type SocialActions } from "./backend-safety";
import { betsInvolving, profileStats } from "./profile-model";
import { userKey } from "./query-keys";
import { listSettlementGroups, receiptHistory } from "./settlement-api";
import { useRelationships } from "./social-queries";
import { throwTomato } from "./tables-api";
import { ticketStatus } from "./ticket-model";
import { usePerson } from "./use-person";

type Tab = "bets" | "settlements";

/**
 * Someone at this table: their record here, your receipts with them, and Report, Block or a tomato. Bets you can't
 * see yet (their Active picks) never show.
 */
export function ProfileDialog({
  userId,
  table,
  bets,
  user,
  expire,
  peopleKey,
  social,
  onOpenBet,
  onOpenChange,
}: {
  userId: string | null;
  table: BackendTable;
  bets: BackendBet[] | undefined;
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
  social: SocialActions;
  onOpenBet: (betId: string) => void;
  onOpenChange: (open: boolean) => void;
}) {
  const [tab, setTab] = useState<Tab>("bets");
  const id = userId ?? user.id;
  const self = id === user.id;
  const person = usePerson(id, user, expire, peopleKey);
  const name = person?.name ?? "…";
  const theirs = betsInvolving(bets ?? [], id, user.id);
  const stats = profileStats(bets ?? [], id);
  const groups = useQuery({
    queryKey: [...userKey(user.id, "settlements"), "groups"],
    queryFn: ({ signal }) => listSettlementGroups(user.id, signal),
    retry: false,
    enabled: !!userId && !self,
  });
  const net = groups.data?.find((group) => group.otherUserId === id)?.netCents;

  return (
    <Dialog
      open={!!userId}
      onOpenChange={(open) => {
        onOpenChange(open);
        if (!open) setTab("bets");
      }}
    >
      <DialogContent className="flex max-h-[85dvh] flex-col gap-4">
        <DialogHeader className="flex-row items-center gap-4 space-y-0 text-left">
          <PersonAvatar
            id={id}
            size="lg"
            className="ring-0"
            user={user}
            expire={expire}
            queryKey={peopleKey}
          />
          <div className="min-w-0">
            <DialogTitle className="break-words font-display text-3xl font-black uppercase leading-none">
              {name}
            </DialogTitle>
            <DialogDescription className="mt-1 font-mono text-xs">
              {!self && net !== undefined && (
                <span
                  className={
                    net > 0n ? "text-win" : net < 0n ? "text-loss" : "text-muted-foreground"
                  }
                >
                  {formatCents(net, true)} settle-ups ·{" "}
                </span>
              )}
              <span className="text-muted-foreground">
                {stats.played} played · {stats.won}W {stats.lost}L ·{" "}
                <span
                  className={
                    stats.netCents > 0 ? "text-win" : stats.netCents < 0 ? "text-loss" : ""
                  }
                >
                  {formatCents(stats.netCents, true)}
                </span>{" "}
                here
              </span>
            </DialogDescription>
          </div>
        </DialogHeader>

        {!self && userId && (
          <SafetyButtons
            userId={id}
            name={name}
            table={table}
            user={user}
            social={social}
            onThrown={() => onOpenChange(false)}
          />
        )}

        <SegmentedTabs
          size="sm"
          ariaLabel="Profile history"
          tabs={
            self
              ? [{ id: "bets", label: "Bets" }]
              : [
                  { id: "bets", label: "Bets" },
                  { id: "settlements", label: "Receipts" },
                ]
          }
          value={self ? "bets" : tab}
          onChange={setTab}
        />

        <ScrollContainer isVisible={false} className="-mx-1 min-h-0 flex-1 px-1">
          {tab === "settlements" && !self && userId ? (
            <Receipts otherId={id} tableId={table.id} user={user} expire={expire} />
          ) : theirs.length === 0 ? (
            <EmptyState className="py-8">No bets here yet.</EmptyState>
          ) : (
            <ul className="space-y-2">
              {theirs.map((bet) => {
                const mode = MODES.find((m) => m.id === bet.modeId)!;
                const pick = bet.picks?.find((p) => p.userId === id)?.choice;
                const delta = bet.deltasCents?.[id];
                return (
                  <li key={bet.id}>
                    <button
                      type="button"
                      onClick={() => {
                        onOpenChange(false);
                        onOpenBet(bet.id);
                      }}
                      className="flex w-full items-center gap-3 rounded-xl border bg-surface p-3 text-left transition-colors hover:border-primary/60"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                          {mode.name}
                          {bet.proposerId === id && " · Proposed"}
                          {pick && ` · Picked ${pick}`}
                        </span>
                        <span className="line-clamp-2 break-words text-sm font-semibold">
                          {bet.question}
                        </span>
                      </span>
                      <span className="flex shrink-0 flex-col items-end gap-1">
                        <StatusBadge
                          status={ticketStatus(bet)}
                          className="px-1.5 py-0.5 text-[8px]"
                        />
                        {delta !== undefined && (
                          <span
                            className={cn(
                              "font-mono text-xs font-bold",
                              delta > 0 ? "text-win" : "text-loss",
                            )}
                          >
                            {formatCents(delta, true)}
                          </span>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </ScrollContainer>
      </DialogContent>
    </Dialog>
  );
}

function SafetyButtons({
  userId,
  name,
  table,
  user,
  social,
  onThrown,
}: {
  userId: string;
  name: string;
  table: BackendTable;
  user: BackendUser;
  social: SocialActions;
  onThrown: () => void;
}) {
  const [report, setReport] = useState(false);
  const [block, setBlock] = useState(false);
  const [throwing, setThrowing] = useState(false);
  const relationships = useRelationships(user.id);
  const blocked = relationships.data?.blockedIds.includes(userId) ?? false;
  const canReact = !table.disabledPermissions[user.id]?.includes("react");

  async function throwOne() {
    setThrowing(true);
    try {
      // A lost response just means one less tomato; a fresh key per tap is fine.
      await throwTomato(table.id, userId, crypto.randomUUID());
      onThrown();
    } catch (error) {
      toast.error(
        error instanceof BackendError && error.status === 429
          ? "One tomato every few seconds."
          : backendErrorMessage(error),
      );
    } finally {
      setThrowing(false);
    }
  }

  return (
    <div className="flex flex-wrap gap-2">
      <Button size="sm" variant="outline" onClick={() => setReport(true)}>
        <Flag /> Report
      </Button>
      <Button size="sm" variant="outline" onClick={() => setBlock(true)}>
        <Ban /> {blocked ? "Unblock" : "Block"}
      </Button>
      <Button
        size="sm"
        variant="outline"
        aria-label={`Throw a tomato at ${name}`}
        title={canReact ? `Throw a tomato at ${name}` : "A host turned off reactions for you"}
        disabled={!canReact || blocked || throwing}
        onClick={() => void throwOne()}
      >
        <span aria-hidden="true" className="text-base leading-none">
          🍅
        </span>{" "}
        Throw
      </Button>
      <ReportDialog
        userId={userId}
        name={name}
        viewerId={user.id}
        actions={social}
        context={{ tableId: table.id }}
        open={report}
        onOpenChange={setReport}
      />
      <BlockDialog
        userId={userId}
        name={name}
        viewerId={user.id}
        actions={social}
        open={block}
        onOpenChange={setBlock}
      />
    </div>
  );
}

/** Your receipts with this person from this table, newest first. */
function Receipts({
  otherId,
  tableId,
  user,
  expire,
}: {
  otherId: string;
  tableId: string;
  user: BackendUser;
  expire: () => void;
}) {
  const query = useQuery({
    queryKey: [...userKey(user.id, "settlements"), "history", otherId],
    queryFn: ({ signal }) => receiptHistory(user.id, otherId, signal),
    retry: false,
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  if (query.isPending) return <p className="py-4 text-sm text-muted-foreground">Loading…</p>;
  if (query.isError) return <p role="alert">{backendErrorMessage(query.error)}</p>;
  const lines = query.data.filter((line) => line.tableId === tableId);
  if (lines.length === 0)
    return <EmptyState className="py-8">No receipts between you here.</EmptyState>;
  return (
    <ul className="divide-y rounded-xl border bg-surface">
      {lines.map((line) => {
        const youPay = line.payerId === user.id;
        return (
          <li key={line.id} className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm">
            <span className="min-w-0">
              <span className="block font-semibold">{youPay ? "You pay" : "They pay you"}</span>
              <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                {line.status.replaceAll("_", " ")} · {dateTimeLabel(line.createdAt)}
              </span>
            </span>
            <span className={cn("scoreboard text-lg", youPay ? "text-loss" : "text-win")}>
              {formatCents(line.amountCents)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ConfirmAction } from "@/components/confirm-action";
import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { dateTimeLabel } from "@/lib/time";
import { cn } from "@/lib/utils";
import type { User } from "@/features/people";
import {
  changeUser,
  listFlagged,
  listReports,
  moderate,
  type ModerationPerson,
  type ModerationReport,
} from "./api";

const REASONS: Record<ModerationReport["reason"], string> = {
  harassment: "Harassment",
  settlement: "Settlement dispute",
  spam: "Spam",
  offensive: "Offensive",
  other: "Other",
};

/**
 * The moderation queue: each report is dismissed, or resolved with a strike against the reported player. A third
 * strike freezes them (they can't sign in, and tables they host pass to the longest-standing member). Below it,
 * players with strikes or a frozen account, with unfreeze and clear strikes.
 */
export function Moderation({ user }: { user: User }) {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<"open" | "all">("open");
  const key = userKey(user.id, "moderation");
  const reports = useQuery({
    queryKey: [...key, "reports", filter],
    queryFn: ({ signal }) => listReports(filter === "all", signal),
    retry: false,
  });
  const flagged = useQuery({
    queryKey: [...key, "flagged"],
    queryFn: ({ signal }) => listFlagged(signal),
    retry: false,
  });
  const act = useMutation({
    mutationFn: async (run: () => Promise<{ strikes: number; frozen: boolean } | null | void>) =>
      run(),
    onSuccess: (result) => {
      if (result && "frozen" in result)
        toast.success(
          result.frozen ? "Third strike: the account is frozen." : `Strike ${result.strikes} of 3.`,
        );
    },
    onError: (error) => toast.error(backendErrorMessage(error)),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  return (
    <div className="space-y-8">
      <section className="space-y-3" aria-labelledby="reports-heading">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 id="reports-heading" className="font-display text-3xl font-bold uppercase">
            Reports
          </h1>
          <SegmentedTabs
            size="sm"
            ariaLabel="Which reports"
            tabs={[
              { id: "open", label: "Open" },
              { id: "all", label: "All" },
            ]}
            value={filter}
            onChange={setFilter}
          />
        </div>
        {reports.isPending ? (
          <p className="text-sm text-muted-foreground">Loading reports…</p>
        ) : reports.isError ? (
          <p role="alert">{backendErrorMessage(reports.error)}</p>
        ) : reports.data.length === 0 ? (
          <EmptyState boxed className="p-6">
            {filter === "open" ? "No open reports." : "No reports yet."}
          </EmptyState>
        ) : (
          <ul className="space-y-3">
            {reports.data.map((report) => (
              <li key={report.id} className="space-y-2 rounded-xl border bg-surface p-4">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-semibold">
                    {REASONS[report.reason]}
                    <span className="ml-2 font-mono text-[10px] uppercase text-muted-foreground">
                      {report.status.toLowerCase()} · {dateTimeLabel(report.createdAt)}
                    </span>
                  </p>
                  {report.tableName && (
                    <span className="text-xs text-muted-foreground">at {report.tableName}</span>
                  )}
                </div>
                <p className="text-sm">
                  <Name person={report.reporter} /> reported <Name person={report.target} />
                </p>
                {report.messageSnapshot && (
                  <blockquote className="border-l-2 pl-3 text-sm text-muted-foreground">
                    “{report.messageSnapshot}”
                  </blockquote>
                )}
                {report.note && <p className="text-sm">Note: {report.note}</p>}
                {report.status === "OPEN" && (
                  <div className="flex flex-wrap gap-2 pt-1">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={act.isPending}
                      onClick={() => act.mutate(() => moderate("dismiss", report.id))}
                    >
                      Dismiss
                    </Button>
                    <ConfirmAction
                      label="Give a strike"
                      title={`Strike ${report.target.username ?? "this player"}?`}
                      description={
                        report.target.strikes >= 2
                          ? "This is their third strike: their account will be frozen and they'll be signed out."
                          : `They'll have ${report.target.strikes + 1} of 3 strikes. Three freezes the account.`
                      }
                      disabled={act.isPending}
                      onConfirm={() => act.mutate(() => moderate("strike", report.id))}
                    />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-3" aria-labelledby="flagged-heading">
        <h2 id="flagged-heading" className="font-display text-2xl font-bold uppercase">
          Strikes and frozen accounts
        </h2>
        {flagged.isPending ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : flagged.isError ? (
          <p role="alert">{backendErrorMessage(flagged.error)}</p>
        ) : flagged.data.length === 0 ? (
          <EmptyState boxed className="p-6">
            Nobody has strikes.
          </EmptyState>
        ) : (
          <ul className="divide-y rounded-xl border bg-surface">
            {flagged.data.map((person) => (
              <li key={person.id} className="flex flex-wrap items-center justify-between gap-3 p-3">
                <span>
                  <Name person={person} />
                  <span className="ml-2 text-xs text-muted-foreground">
                    {person.strikes} of 3 strikes
                  </span>
                </span>
                <span className="flex gap-2">
                  {person.status === "FROZEN" && (
                    <Button
                      size="sm"
                      disabled={act.isPending}
                      onClick={() => act.mutate(() => changeUser("unfreeze", person.id))}
                    >
                      Unfreeze
                    </Button>
                  )}
                  {person.strikes > 0 && (
                    <ConfirmAction
                      label="Clear strikes"
                      title={`Clear ${person.username ?? "their"} strikes?`}
                      description="Resets them to zero. It doesn't unfreeze a frozen account."
                      disabled={act.isPending}
                      onConfirm={() => act.mutate(() => changeUser("clear-strikes", person.id))}
                    />
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Name({ person }: { person: ModerationPerson }) {
  return (
    <span className="font-semibold">
      {person.username ?? "Deleted user"}
      {person.status === "FROZEN" && (
        <span
          className={cn(
            "ml-1.5 rounded bg-loss/15 px-1.5 py-0.5 font-mono text-[10px] font-bold uppercase text-loss",
          )}
        >
          Frozen
        </span>
      )}
    </span>
  );
}

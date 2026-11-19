import { useState } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { LoadMore } from "@/components/load-more";
import { PageEdge } from "@/components/client-pages";
import { ScrollContainer } from "@/components/scroll-container";
import { toast } from "sonner";
import { ConfirmAction } from "@/components/confirm-action";
import { EmptyState } from "@/components/empty-state";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Button } from "@/components/ui/button";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { dateTimeLabel } from "@/lib/time";
import type { User } from "@/features/people";
import {
  changeUser,
  listFlagged,
  listReports,
  moderate,
  type ModerationPerson,
  type ModerationReport,
} from "./api";
import { NameTag } from "./avatar";
import { useDebounced } from "@/lib/use-debounced";
import { SECTION_HEADING } from "./players";
import { Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { LoadingList } from "@/components/loading";

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
export function Moderation({
  user,
  onOpenPlayer,
}: {
  user: User;
  /** Opens a player's audit from their name. */
  onOpenPlayer?: (id: string) => void;
}) {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<"open" | "all">("open");
  const key = userKey(user.id, "moderation");
  const reports = useInfiniteQuery({
    queryKey: [...key, "reports", filter],
    queryFn: ({ pageParam, signal }) => listReports(filter === "all", pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const [search, setSearch] = useState("");
  const term = useDebounced(search.trim().toLowerCase());
  const flagged = useInfiniteQuery({
    queryKey: [...key, "flagged", term],
    queryFn: ({ pageParam, signal }) => listFlagged(pageParam, signal, term),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const reportList = reports.data?.pages.flatMap((page) => page.items) ?? [];
  const flaggedList = flagged.data?.pages.flatMap((page) => page.items) ?? [];
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
          <h2 id="reports-heading" className={SECTION_HEADING}>
            Reports
          </h2>
          <SegmentedTabs
            size="sm"
            ariaLabel="Which reports"
            tabs={[
              { id: "open", label: "Open" },
              { id: "all", label: "All" },
            ]}
            value={filter}
            onChange={setFilter}
            className="w-48"
          />
        </div>
        {reports.isPending ? (
          <LoadingList label="Loading reports" rows={3} rowClassName="h-24" />
        ) : reports.isError ? (
          <p role="alert">{backendErrorMessage(reports.error)}</p>
        ) : reportList.length === 0 ? (
          <EmptyState boxed className="p-6">
            {filter === "open" ? "No open reports." : "No reports yet."}
          </EmptyState>
        ) : (
          // A sideways row of cards, like the Friends page; the next page loads as you near the end.
          <ScrollContainer
            isHorizontal
            isVisible={false}
            aria-label="Reports"
            className="snap-x gap-3 pb-2 md:scrollbar-thin"
          >
            {reportList.map((report) => (
              <article
                key={report.id}
                className="flex w-80 shrink-0 snap-start flex-col gap-2 rounded-xl border bg-surface p-4"
              >
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
                  <Name person={report.reporter} onOpen={onOpenPlayer} /> reported{" "}
                  <Name person={report.target} onOpen={onOpenPlayer} />
                </p>
                {report.messageSnapshot && (
                  <blockquote className="border-l-2 pl-3 text-sm text-muted-foreground">
                    “{report.messageSnapshot}”
                  </blockquote>
                )}
                {report.note && <p className="text-sm">Note: {report.note}</p>}
                {report.status === "OPEN" && (
                  <div className="mt-auto flex flex-wrap gap-2 pt-1">
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
              </article>
            ))}
            {reports.hasNextPage && !reports.isFetchingNextPage && (
              <PageEdge
                onMore={() => void reports.fetchNextPage()}
                label="More"
                className="w-24 shrink-0 self-stretch"
              />
            )}
          </ScrollContainer>
        )}
      </section>

      <section className="space-y-3" aria-labelledby="flagged-heading">
        <div className="flex flex-wrap items-center gap-2">
          <h2 id="flagged-heading" className={SECTION_HEADING}>
            Strikes and frozen accounts
          </h2>
          <div className="relative ml-auto w-full sm:w-64">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              aria-label="Search strikes and frozen accounts"
              placeholder="Search usernames"
              className="h-9 pl-8"
              value={search}
              maxLength={15}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
        </div>
        {flagged.isPending ? (
          <LoadingList label="Loading flagged accounts" rows={2} rowClassName="h-12" />
        ) : flagged.isError ? (
          <p role="alert">{backendErrorMessage(flagged.error)}</p>
        ) : flaggedList.length === 0 ? (
          <EmptyState boxed className="p-6">
            {term ? "No flagged usernames start with that." : "Nobody has strikes."}
          </EmptyState>
        ) : (
          <ul className="divide-y rounded-xl border bg-surface">
            {flaggedList.map((person) => (
              <li key={person.id} className="flex flex-wrap items-center justify-between gap-3 p-3">
                <span>
                  <Name person={person} onOpen={onOpenPlayer} />
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
            {flagged.hasNextPage && (
              <li className="p-3">
                <LoadMore
                  loading={flagged.isFetchingNextPage}
                  onLoad={() => void flagged.fetchNextPage()}
                />
              </li>
            )}
          </ul>
        )}
      </section>
    </div>
  );
}

function Name({
  person,
  onOpen,
}: {
  person: ModerationPerson;
  onOpen?: ((id: string) => void) | undefined;
}) {
  return (
    <span className="inline-flex items-center gap-1.5 align-middle">
      <NameTag
        name={person.username}
        hue={person.hue}
        fallback="Deleted user"
        onOpen={onOpen ? () => onOpen(person.id) : undefined}
      />
      {person.status === "FROZEN" && (
        <span className="rounded bg-loss/15 px-1.5 py-0.5 font-mono text-[10px] font-bold uppercase text-loss">
          Frozen
        </span>
      )}
    </span>
  );
}

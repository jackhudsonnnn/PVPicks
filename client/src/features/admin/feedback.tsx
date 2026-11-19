import { useState } from "react";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { ExternalLink } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { PageEdge } from "@/components/client-pages";
import { LoadingList } from "@/components/loading";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { NameTag } from "./avatar";
import {
  FEEDBACK_KINDS,
  listFeedback,
  updateFeedback,
  type FeedbackItem,
  type FeedbackKind,
} from "./dashboard-api";
import { ago, stamp } from "./format";
import { Chip, SECTION_HEADING } from "./players";

const KIND_LABELS: Record<FeedbackKind, string> = {
  mode: "Mode idea",
  perk: "Perk idea",
  gif: "GIF",
  bug: "Bug",
  other: "Other",
};

type Status = "new" | "done" | "all";

/**
 * Player feedback as a sideways row of cards, 50 at a time (more load as you near the end). Filter by status (a
 * dropdown) and kind; each card takes a private note and is marked Done or reopened.
 */
export function FeedbackInbox({
  user,
  onOpenPlayer,
}: {
  user: User;
  onOpenPlayer: (id: string) => void;
}) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>("new");
  const [kind, setKind] = useState<"all" | FeedbackKind>("all");
  const key = userKey(user.id, "admin-feedback");
  const feedback = useInfiniteQuery({
    queryKey: [...key, status, kind],
    queryFn: ({ pageParam, signal }) =>
      listFeedback({ status, kind: kind === "all" ? null : kind }, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
    retry: false,
  });
  const items = feedback.data?.pages.flatMap((page) => page.items) ?? [];
  const counts = feedback.data?.pages[0]?.counts;

  return (
    <section className="space-y-4" aria-labelledby="feedback-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="feedback-heading" className={SECTION_HEADING}>
          Feedback
        </h2>
        <Select value={status} onValueChange={(value) => setStatus(value as Status)}>
          <SelectTrigger aria-label="Status" className="h-9 w-40 bg-surface">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="new">New{counts ? ` · ${counts.open}` : ""}</SelectItem>
            <SelectItem value="done">Done{counts ? ` · ${counts.done}` : ""}</SelectItem>
            <SelectItem value="all">All</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <SegmentedTabs
        size="sm"
        ariaLabel="Kind"
        tabs={[
          { id: "all", label: "All" },
          ...FEEDBACK_KINDS.map((id) => ({ id, label: KIND_LABELS[id] })),
        ]}
        value={kind}
        onChange={setKind}
      />
      {feedback.isPending ? (
        <LoadingList label="Loading feedback" rows={1} rowClassName="h-64" />
      ) : feedback.isError ? (
        <p role="alert">{backendErrorMessage(feedback.error)}</p>
      ) : items.length === 0 ? (
        <EmptyState boxed className="p-6">
          {status === "new" ? "Inbox zero." : "Nothing here."}
        </EmptyState>
      ) : (
        <ScrollContainer
          isHorizontal
          isVisible={false}
          aria-label="Feedback"
          className="snap-x gap-3 pb-2 md:scrollbar-thin"
        >
          {items.map((item) => (
            <FeedbackCard
              key={item.id}
              item={item}
              onOpenPlayer={onOpenPlayer}
              onSaved={() => void queryClient.invalidateQueries({ queryKey: key })}
            />
          ))}
          {feedback.hasNextPage && !feedback.isFetchingNextPage && (
            <PageEdge
              onMore={() => void feedback.fetchNextPage()}
              label="Load more"
              className="w-24 shrink-0 self-stretch"
            />
          )}
        </ScrollContainer>
      )}
    </section>
  );
}

function FeedbackCard({
  item,
  onOpenPlayer,
  onSaved,
}: {
  item: FeedbackItem;
  onOpenPlayer: (id: string) => void;
  onSaved: () => void;
}) {
  const [note, setNote] = useState(item.adminNote ?? "");
  const save = useMutation({
    mutationFn: (status: "NEW" | "DONE") => updateFeedback(item.id, status, note),
    onSuccess: (_, status) => {
      toast.success(
        status === item.status ? "Note saved." : status === "DONE" ? "Marked done." : "Reopened.",
      );
      onSaved();
    },
    onError: (error) => toast.error(backendErrorMessage(error)),
  });
  const changed = (item.adminNote ?? "") !== note.trim();
  return (
    <article className="flex w-80 shrink-0 snap-start flex-col gap-3 rounded-xl border bg-surface p-4">
      <div className="flex items-center justify-between gap-2">
        <NameTag
          name={item.username}
          hue={item.hue}
          size="sm"
          onOpen={() => onOpenPlayer(item.userId)}
        />
        <span className="shrink-0 text-xs text-muted-foreground" title={stamp(item.createdAt)}>
          {ago(item.createdAt)}
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        <Chip tone={item.kind === "bug" ? "bad" : "muted"}>{KIND_LABELS[item.kind]}</Chip>
        {item.status === "DONE" && <Chip tone="good">Done</Chip>}
      </div>
      <p className="line-clamp-6 whitespace-pre-wrap break-words" title={item.text}>
        {item.text}
      </p>
      {item.link && (
        <a
          href={item.link}
          target="_blank"
          rel="noreferrer noopener"
          className="inline-flex items-center gap-1 truncate text-sm text-primary underline-offset-2 hover:underline"
        >
          <ExternalLink className="size-3.5 shrink-0" />
          <span className="truncate">{item.link}</span>
        </a>
      )}
      <div className="mt-auto space-y-2">
        <Textarea
          aria-label="Private note"
          placeholder="Private note (only admins see it)"
          value={note}
          maxLength={500}
          rows={2}
          className="resize-none text-sm"
          onChange={(event) => setNote(event.target.value)}
        />
        {item.handledBy && item.handledAt && (
          <p className="text-[11px] text-muted-foreground">
            Done by {item.handledBy}, {stamp(item.handledAt)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          {changed && (
            <Button
              size="sm"
              variant="outline"
              disabled={save.isPending}
              onClick={() => save.mutate(item.status)}
            >
              Save note
            </Button>
          )}
          {item.status === "DONE" ? (
            <Button
              size="sm"
              variant="outline"
              disabled={save.isPending}
              onClick={() => save.mutate("NEW")}
            >
              Reopen
            </Button>
          ) : (
            <Button size="sm" disabled={save.isPending} onClick={() => save.mutate("DONE")}>
              Mark done
            </Button>
          )}
        </div>
      </div>
    </article>
  );
}

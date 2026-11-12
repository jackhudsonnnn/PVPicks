import { EmptyState } from "@/components/empty-state";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Bell, BellOff, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { useNotifications } from "../notifications";
import { ToneIcon } from "./tone-icon";
import { PAGE_SIZE, pageAfter, timeAgo, unreadCount, type NotificationItem } from "../history";

type Filter = "all" | "unread";
const FILTERS = [
  { id: "all", label: "All" },
  { id: "unread", label: "Unread" },
] as const;

/** Bell in the nav bar: unread badge plus a paginated inbox of past notifications. */
export function NotificationBell({
  align = "end",
  className,
}: {
  align?: "start" | "end";
  className?: string;
}) {
  const { history, markAllRead, clearHistory } = useNotifications();
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState<Filter>("all");
  const [pages, setPages] = useState(1);
  const unread = unreadCount(history);

  function onOpenChange(next: boolean) {
    setOpen(next);
    // Keep unread highlights while the inbox is open; clear them once it's dismissed.
    if (!next) {
      markAllRead();
      setPages(1);
    }
  }

  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
          className={cn("relative shrink-0", className)}
        >
          <Bell className="size-5" />
          {unread > 0 && (
            <span className="absolute right-0.5 top-0.5 min-w-4 rounded-full bg-primary px-1 font-mono text-[9px] font-bold leading-4 text-primary-foreground">
              {unread > 99 ? "99+" : unread}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align={align}
        sideOffset={8}
        className="flex max-h-[min(70dvh,560px)] w-[min(92vw,380px)] flex-col gap-2 rounded-2xl bg-surface p-2"
      >
        <div className="flex items-center justify-between gap-2 px-2 pt-1">
          <h2 className="font-display text-xl font-black uppercase leading-none">Notifications</h2>
          <Button
            size="sm"
            variant="ghost"
            className="h-7 px-2 text-xs text-muted-foreground"
            disabled={history.length === 0}
            onClick={clearHistory}
          >
            <Trash2 /> Clear
          </Button>
        </div>
        <SegmentedTabs
          size="sm"
          ariaLabel="Notification filter"
          className="grid-cols-2"
          tabs={FILTERS}
          value={filter}
          onChange={(f) => {
            setFilter(f);
            setPages(1);
          }}
        />
        <Inbox
          history={history}
          filter={filter}
          pages={pages}
          onLoadMore={() => setPages((p) => p + 1)}
          onOpenItem={() => onOpenChange(false)}
        />
      </PopoverContent>
    </Popover>
  );
}

function Inbox({
  history,
  filter,
  pages,
  onLoadMore,
  onOpenItem,
}: {
  history: NotificationItem[];
  filter: Filter;
  pages: number;
  onLoadMore: () => void;
  onOpenItem: () => void;
}) {
  const navigate = useNavigate();
  const sentinel = useRef<HTMLDivElement>(null);
  const now = Date.now();

  // Only walk as many pages as are shown; recomputed when history or filter changes.
  // TODO(perf): with a server feed, fetch each page by cursor (useInfiniteQuery) instead of
  // filtering the full local list, and virtualize the rows if pages get long.
  const { items, hasMore } = useMemo(() => {
    const source = filter === "unread" ? history.filter((i) => !i.read) : history;
    const shown: NotificationItem[] = [];
    let cursor: string | null = null;
    for (let p = 0; p < pages; p++) {
      const page = pageAfter(source, cursor, PAGE_SIZE);
      shown.push(...page.items);
      cursor = page.nextCursor;
      if (cursor === null) break;
    }
    return { items: shown, hasMore: cursor !== null };
  }, [history, filter, pages]);

  // Infinite scroll: load the next page when the bottom of the list comes into view.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasMore) return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) onLoadMore();
    });
    io.observe(el);
    return () => io.disconnect();
  }, [hasMore, onLoadMore, items.length]);

  if (items.length === 0) {
    return (
      <EmptyState icon={<BellOff className="size-6" />} className="px-4 py-10">
        {filter === "unread" ? "You're all caught up." : "No notifications yet."}
      </EmptyState>
    );
  }

  return (
    <ScrollContainer isVisible={false} className="flex-1">
      <ul className="space-y-1">
        <AnimatedList key={filter}>
          {items.map((i) => (
            <AnimatedItem as="li" key={i.id}>
              <button
                type="button"
                disabled={!i.tableId}
                onClick={() => {
                  if (!i.tableId) return;
                  onOpenItem();
                  navigate({
                    to: "/tables/$tableId",
                    params: { tableId: i.tableId },
                    search: i.betId ? { bet: i.betId } : {},
                  });
                }}
                className={cn(
                  "flex w-full items-start gap-2.5 rounded-xl px-2.5 py-2 text-left transition-colors enabled:hover:bg-sidebar-accent/60",
                  !i.read && "bg-primary/10",
                )}
              >
                <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-lg bg-sidebar">
                  <ToneIcon tone={i.tone} className="size-3.5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 break-words text-sm font-semibold leading-snug">
                      {i.title}
                    </span>
                    <span className="shrink-0 font-mono text-[10px] uppercase text-muted-foreground">
                      {timeAgo(i.at, now)}
                    </span>
                    {!i.read && (
                      <span
                        className="size-1.5 shrink-0 rounded-full bg-primary"
                        aria-label="Unread"
                      />
                    )}
                  </span>
                  {i.description && (
                    <span className="mt-0.5 line-clamp-2 block text-xs text-muted-foreground">
                      {i.description}
                    </span>
                  )}
                </span>
              </button>
            </AnimatedItem>
          ))}
        </AnimatedList>
      </ul>
      {hasMore && (
        <div ref={sentinel} className="flex justify-center py-2">
          <Button size="sm" variant="ghost" className="h-7 text-xs" onClick={onLoadMore}>
            Load older
          </Button>
        </div>
      )}
    </ScrollContainer>
  );
}

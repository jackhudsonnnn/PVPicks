import { useCallback, useEffect, useRef, useState } from "react";
import { isUnauthorized } from "@/lib/api-client";
import { getChatUnread, getChatWindow, type ChatMessage } from "./api";
import { appendNewer, mergeRange, prependOlder } from "./model";

const pageSize = 25;
/**
 * How many newer pages a refresh follows before giving up on paging and opening the latest window instead.
 * A busy table can post hundreds of messages while you're away; paging through them all froze the chat.
 */
const catchUpPages = 2;

type ChatState = {
  status: "loading" | "ready" | "error";
  error: unknown;
  messages: ChatMessage[];
  /** The first message that was unread when the chat opened; it gets a "New" divider. */
  firstUnreadId: string | null;
  olderCursor: string | null;
  /** Whether the newest loaded message is the newest visible message. */
  atEnd: boolean;
  /** Changes arrived while older history was on screen. */
  newBelow: boolean;
  busy: boolean;
};

const initial: ChatState = {
  status: "loading",
  error: null,
  messages: [],
  firstUnreadId: null,
  olderCursor: null,
  atEnd: false,
  newBelow: false,
  busy: false,
};

/**
 * Loads a table's chat as contiguous windows and keeps it current. Operations run one at a time, so a change
 * that arrives during a refresh schedules another refresh instead of being lost.
 */
export function useBackendChat(
  tableId: string,
  expire: () => void,
  { readOnly = false }: { readOnly?: boolean } = {},
) {
  const [state, setState] = useState<ChatState>(initial);
  // The source of truth for queued operations: updated synchronously, so an operation that awaits
  // several requests always builds on the latest messages rather than the last rendered ones.
  const current = useRef(state);
  // The latest nonempty window's next cursor; kept even after reaching the end so later arrivals load.
  const newerCursor = useRef<string | null>(null);
  const queue = useRef<Promise<void>>(Promise.resolve());
  const alive = useRef(true);

  const update = useCallback((change: Partial<ChatState>) => {
    current.current = { ...current.current, ...change };
    if (alive.current) setState(current.current);
  }, []);

  const run = useCallback(
    (operation: () => Promise<void>) => {
      const next = queue.current.then(async () => {
        if (!alive.current) return;
        update({ busy: true });
        try {
          await operation();
        } catch (error) {
          if (isUnauthorized(error)) expire();
          else if (current.current.status === "loading") update({ status: "error", error });
        } finally {
          update({ busy: false });
        }
      });
      queue.current = next;
      return next;
    },
    [expire, update],
  );

  /** Loads up to {@code maxPages} newer pages; true once the newest message is loaded. */
  const loadNewer = useCallback(
    async (maxPages: number) => {
      let messages = current.current.messages;
      for (let page = 0; page < maxPages; page++) {
        if (!newerCursor.current) return true;
        const window = await getChatWindow(tableId, { after: newerCursor.current });
        messages = appendNewer(messages, window.items);
        if (window.items.length > 0 && window.nextCursor) newerCursor.current = window.nextCursor;
        if (window.items.length < pageSize) {
          update({ messages, atEnd: true, newBelow: false });
          return true;
        }
        update({ messages });
      }
      return false;
    },
    [tableId, update],
  );

  /** Replaces what's loaded with the latest window, however far behind it was. */
  const openLatest = useCallback(async () => {
    const latest = await getChatWindow(tableId, { latest: true });
    newerCursor.current = latest.nextCursor;
    update({
      messages: latest.items,
      olderCursor: latest.previousCursor,
      atEnd: true,
      newBelow: false,
    });
  }, [tableId, update]);

  const load = useCallback(
    () =>
      run(async () => {
        // Archives have no read position: open them at the latest messages.
        const unread = readOnly ? { firstUnreadMessageId: null } : await getChatUnread(tableId);
        const window = await getChatWindow(
          tableId,
          unread.firstUnreadMessageId ? { around: unread.firstUnreadMessageId } : { latest: true },
        );
        newerCursor.current = window.nextCursor;
        update({
          status: "ready",
          error: null,
          messages: window.items,
          firstUnreadId: unread.firstUnreadMessageId,
          olderCursor: window.previousCursor,
          atEnd: !unread.firstUnreadMessageId,
        });
        // One more page at most: a busy table can be hundreds behind. Load newer or Jump to latest goes on.
        if (unread.firstUnreadMessageId) await loadNewer(1);
      }),
    [loadNewer, readOnly, run, tableId, update],
  );

  // A refresh is waiting in the queue; more changes before it starts are covered by it.
  const refreshQueued = useRef(false);

  useEffect(() => {
    alive.current = true;
    refreshQueued.current = false;
    void load();
    return () => {
      alive.current = false;
    };
  }, [load]);

  const loadOlder = useCallback(
    () =>
      run(async () => {
        const cursor = current.current.olderCursor;
        if (!cursor) return;
        const window = await getChatWindow(tableId, { before: cursor });
        update({
          messages: prependOlder(window.items, current.current.messages),
          olderCursor: window.previousCursor,
        });
      }),
    [run, tableId, update],
  );

  /** Something changed: fetch arrivals, then refresh the latest window for reactions and removals. */
  const refresh = useCallback(() => {
    if (refreshQueued.current) return queue.current;
    refreshQueued.current = true;
    return run(async () => {
      refreshQueued.current = false;
      if (current.current.status !== "ready") return;
      if (!current.current.atEnd) {
        update({ newBelow: true });
        return;
      }
      if (!newerCursor.current) {
        // An empty chat has no cursor to continue from, so start from the latest window.
        const latest = await getChatWindow(tableId, { latest: true });
        if (latest.nextCursor) newerCursor.current = latest.nextCursor;
        update({
          messages: appendNewer(current.current.messages, latest.items),
          olderCursor: current.current.olderCursor ?? latest.previousCursor,
        });
        return;
      }
      if (!(await loadNewer(catchUpPages))) {
        await openLatest();
        return;
      }
      const latest = await getChatWindow(tableId, { latest: true });
      update({ messages: mergeRange(current.current.messages, latest.items) });
    });
  }, [loadNewer, openLatest, run, tableId, update]);

  /** Re-reads the window around one message, for example after reacting to it. */
  const refreshAround = useCallback(
    (messageId: string) =>
      run(async () => {
        const window = await getChatWindow(tableId, { around: messageId });
        update({ messages: mergeRange(current.current.messages, window.items) });
      }),
    [run, tableId, update],
  );

  const loadNewerPage = useCallback(
    () =>
      run(async () => {
        await loadNewer(1);
      }),
    [loadNewer, run],
  );

  const jumpToLatest = useCallback(() => run(openLatest), [openLatest, run]);

  return {
    state,
    reload: load,
    loadOlder,
    loadNewer: loadNewerPage,
    refresh,
    refreshAround,
    jumpToLatest,
  };
}

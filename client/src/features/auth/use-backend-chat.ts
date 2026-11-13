import { useCallback, useEffect, useRef, useState } from "react";
import { BackendError } from "./backend-api";
import { getChatUnread, getChatWindow, type ChatMessage } from "./chat-api";
import { appendNewer, mergeRange, prependOlder } from "./chat-model";

const pageSize = 25;

export type ChatState = {
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
export function useBackendChat(tableId: string, expire: () => void) {
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
          if (error instanceof BackendError && error.status === 401) expire();
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

  const loadNewer = useCallback(async () => {
    let messages = current.current.messages;
    for (;;) {
      if (!newerCursor.current) break;
      const window = await getChatWindow(tableId, { after: newerCursor.current });
      messages = appendNewer(messages, window.items);
      if (window.items.length > 0 && window.nextCursor) newerCursor.current = window.nextCursor;
      if (window.items.length < pageSize) {
        update({ messages, atEnd: true, newBelow: false });
        return;
      }
      update({ messages });
    }
  }, [tableId, update]);

  const load = useCallback(
    () =>
      run(async () => {
        const unread = await getChatUnread(tableId);
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
        if (unread.firstUnreadMessageId) await loadNewer();
      }),
    [loadNewer, run, tableId, update],
  );

  useEffect(() => {
    alive.current = true;
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
  const refresh = useCallback(
    () =>
      run(async () => {
        if (current.current.status !== "ready") return;
        if (!current.current.atEnd) {
          update({ newBelow: true });
          return;
        }
        await loadNewer();
        const latest = await getChatWindow(tableId, { latest: true });
        update({ messages: mergeRange(current.current.messages, latest.items) });
      }),
    [loadNewer, run, tableId, update],
  );

  /** Re-reads the window around one message, for example after reacting to it. */
  const refreshAround = useCallback(
    (messageId: string) =>
      run(async () => {
        const window = await getChatWindow(tableId, { around: messageId });
        update({ messages: mergeRange(current.current.messages, window.items) });
      }),
    [run, tableId, update],
  );

  const jumpToLatest = useCallback(() => run(loadNewer), [loadNewer, run]);

  return { state, reload: load, loadOlder, refresh, refreshAround, jumpToLatest };
}

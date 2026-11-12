import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { firstUnreadIndex } from "./unread";
import {
  followChatWindow,
  initialChatWindow,
  latestChatWindow,
  pageChatWindow,
  type ChatWindow,
} from "./chat-window";
import type { Message } from "./types";

type Position =
  | { kind: "bottom" }
  | { kind: "unread" }
  | { kind: "anchor"; id: string; offset: number; scrollTop: number };

/** Keeps rows bounded and preserves a visible message's position even with variable-height cards/GIFs. */
export function useChatWindow(
  messages: Message[],
  loaded: boolean,
  lastRead: string | undefined,
  me: string,
  save: (id: string | undefined) => void,
) {
  const scroller = useRef<HTMLDivElement>(null);
  const [window, setWindow] = useState<ChatWindow | null>(null);
  const [dividerId, setDividerId] = useState<string | null>(null);
  const [atLatest, setAtLatest] = useState(false);
  const position = useRef<Position | null>(null);
  const restoring = useRef(false);
  const following = useRef(false);
  const previousLength = useRef(messages.length);
  const restoreFrame = useRef<number | null>(null);
  useEffect(
    () => () => {
      if (restoreFrame.current !== null) cancelAnimationFrame(restoreFrame.current);
    },
    [],
  );

  useEffect(() => {
    if (!loaded || window) return;
    const unread = firstUnreadIndex(messages, lastRead, me);
    setDividerId(messages[unread]?.id ?? null);
    position.current = { kind: unread < 0 ? "bottom" : "unread" };
    following.current = unread < 0;
    setWindow(initialChatWindow(messages.length, unread));
  }, [loaded, window, messages, lastRead, me]);

  const captureAnchor = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const top = el.getBoundingClientRect().top;
    const row = Array.from(el.querySelectorAll<HTMLElement>("[data-chat-message]")).find(
      (node) => node.getBoundingClientRect().bottom > top,
    );
    if (row?.dataset["chatMessage"]) {
      position.current = {
        kind: "anchor",
        id: row.dataset["chatMessage"],
        offset: row.getBoundingClientRect().top - top,
        scrollTop: el.scrollTop,
      };
    }
  }, []);

  // Restore before paint: pruning above the viewport must not move the message being read.
  useLayoutEffect(() => {
    const el = scroller.current;
    const pending = position.current;
    if (!el || !window || !pending) return;
    position.current = null;
    restoring.current = true;
    if (pending.kind === "bottom") {
      el.scrollTop = el.scrollHeight;
    } else if (pending.kind === "unread") {
      const line = el.querySelector<HTMLElement>("[data-unread-line]");
      if (line)
        el.scrollTop += line.getBoundingClientRect().top - el.getBoundingClientRect().top - 8;
    } else {
      const row = Array.from(el.querySelectorAll<HTMLElement>("[data-chat-message]")).find(
        (node) => node.dataset["chatMessage"] === pending.id,
      );
      if (row)
        el.scrollTop +=
          row.getBoundingClientRect().top - el.getBoundingClientRect().top - pending.offset;
      else el.scrollTop = pending.scrollTop;
    }
    const bottom =
      window.end === messages.length && el.scrollHeight - el.scrollTop - el.clientHeight < 24;
    // A send/jump can be batched with an incoming message before this old window extends.
    // Preserve the explicit follow intent so the arrival effect includes that message.
    following.current = pending.kind === "bottom" || bottom;
    setAtLatest(bottom);
    if (bottom) save(messages[messages.length - 1]?.id);
    if (restoreFrame.current !== null) cancelAnimationFrame(restoreFrame.current);
    restoreFrame.current = requestAnimationFrame(() => {
      restoring.current = false;
      restoreFrame.current = null;
    });
  }, [window, messages, save]);

  useEffect(() => {
    const changed = previousLength.current !== messages.length;
    previousLength.current = messages.length;
    if (!changed || !window || !following.current) return;
    position.current = { kind: "bottom" };
    setWindow(followChatWindow(window, messages.length));
  }, [messages.length, window]);

  function onScroll() {
    const el = scroller.current;
    if (!el || !window || restoring.current) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    const bottom = window.end === messages.length && distance < 24;
    following.current = bottom;
    setAtLatest(bottom);
    // Only advance through messages actually seen, never through the unmounted gap.
    const viewportBottom = el.getBoundingClientRect().bottom;
    const rows = Array.from(el.querySelectorAll<HTMLElement>("[data-chat-message]"));
    const seen = rows.filter((row) => row.getBoundingClientRect().bottom <= viewportBottom).at(-1);
    if (seen) save(seen.dataset["chatMessage"]);
    if (bottom) save(messages[messages.length - 1]?.id);
    const direction =
      el.scrollTop < 80 && window.start > 0
        ? "older"
        : distance < 80 && window.end < messages.length
          ? "newer"
          : null;
    if (!direction) return;
    captureAnchor();
    restoring.current = true;
    setWindow(pageChatWindow(window, messages.length, direction));
  }

  function jumpToLatest() {
    following.current = true;
    position.current = { kind: "bottom" };
    setWindow(latestChatWindow(messages.length));
    save(messages[messages.length - 1]?.id);
  }

  return { scroller, window, dividerId, atLatest, onScroll, jumpToLatest };
}

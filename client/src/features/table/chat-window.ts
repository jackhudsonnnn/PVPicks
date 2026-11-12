/** Exclusive end offsets; replace these local offsets with message cursors at the API boundary. */
export interface ChatWindow {
  start: number;
  end: number;
}

const CHAT_INITIAL_SIZE = 30;
const CHAT_PAGE_SIZE = 25;
export const CHAT_MAX_MOUNTED = 60;
const UNREAD_CONTEXT = 5;

export function latestChatWindow(total: number): ChatWindow {
  return { start: Math.max(0, total - CHAT_INITIAL_SIZE), end: total };
}

export function initialChatWindow(total: number, firstUnread: number): ChatWindow {
  if (firstUnread < 0) return latestChatWindow(total);
  const start = Math.max(0, firstUnread - UNREAD_CONTEXT);
  return { start, end: Math.min(total, start + CHAT_INITIAL_SIZE) };
}

export function pageChatWindow(
  window: ChatWindow,
  total: number,
  direction: "older" | "newer",
): ChatWindow {
  if (direction === "older") {
    const start = Math.max(0, window.start - CHAT_PAGE_SIZE);
    return { start, end: Math.min(window.end, start + CHAT_MAX_MOUNTED, total) };
  }
  const end = Math.min(total, window.end + CHAT_PAGE_SIZE);
  return { start: Math.max(window.start, end - CHAT_MAX_MOUNTED), end };
}

/** Follow incoming messages without ever expanding the mounted history beyond its cap. */
export function followChatWindow(window: ChatWindow, total: number): ChatWindow {
  return { start: Math.max(window.start, total - CHAT_MAX_MOUNTED), end: total };
}

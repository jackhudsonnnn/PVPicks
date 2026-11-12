/**
 * Chat unread rules. Each table stores only the id of the last message you've read.
 * TODO: move last-read ids to the server when accounts exist.
 */
interface MessageLike {
  id: string;
  authorId?: string | undefined;
}

/**
 * Index of the first unread message, or -1 when you're caught up.
 * Never opened (no last-read id) means everything is unread. Your own messages never count.
 */
export function firstUnreadIndex(
  messages: MessageLike[],
  lastReadId: string | undefined,
  me: string,
) {
  let start = 0;
  if (lastReadId) {
    const i = messages.findIndex((m) => m.id === lastReadId);
    if (i === -1) return -1; // last-read message is gone; treat as caught up
    start = i + 1;
  }
  for (let i = start; i < messages.length; i++) if (messages[i]!.authorId !== me) return i;
  return -1;
}

export function unreadCount(messages: MessageLike[], lastReadId: string | undefined, me: string) {
  const i = firstUnreadIndex(messages, lastReadId, me);
  return i === -1 ? 0 : messages.slice(i).filter((m) => m.authorId !== me).length;
}

/** Reading up to `id` only moves forward, never back. */
export function advanceRead(messages: MessageLike[], current: string | undefined, id: string) {
  const cur = current ? messages.findIndex((m) => m.id === current) : -1;
  const next = messages.findIndex((m) => m.id === id);
  return next > cur ? id : current;
}

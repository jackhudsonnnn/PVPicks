import type { NotificationEvent } from "./events";

/** Visual tone of a notification; the UI maps it to an icon and color. */
export type NotificationTone =
  | "error"
  | "chat"
  | "new"
  | "locked"
  | "won"
  | "lost"
  | "resolved"
  | "washed"
  | "invite"
  | "settle";

/** One entry in the notification inbox (also what a toast shows). */
export interface NotificationItem {
  id: string;
  tone: NotificationTone;
  title: string;
  description?: string | undefined;
  /** Table to open when the item is tapped. */
  tableId?: string | undefined;
  /** Ticket to focus when the table opens. */
  betId?: string | undefined;
  /** Epoch ms. */
  at: number;
  read: boolean;
}

/**
 * Most items kept in the inbox (and in the local cache). Older ones drop off.
 * TODO(backend): once notifications live on the server, keep only the newest page
 * locally and fetch older pages on demand instead of capping history here.
 */
export const HISTORY_CAP = 200;
export const PAGE_SIZE = 15;

/** Turn a store event into inbox content. `nameOf` resolves a user id to a username. */
export function describeEvent(
  e: NotificationEvent,
  me: string,
  nameOf: (id: string) => string,
  fmt: (n: number) => string,
): Omit<NotificationItem, "id" | "at" | "read"> | null {
  if (e.kind === "error") return { tone: "error", title: e.title, description: e.description };
  if (e.kind === "balanceChanged") {
    const signed = (n: number) => `${n > 0 ? "+" : n < 0 ? "−" : ""}$${fmt(Math.abs(n))}`;
    return { tone: "settle", title: `Settle-up with ${nameOf(e.otherId)} changed`, description: `${signed(e.before)} → ${signed(e.after)}. Pending payment or forgiveness reset. Review in Friends and check any money already sent privately.` };
  }
  if (e.kind === "invite")
    return e.joined
      ? {
          tone: "invite",
          title: `${nameOf(e.fromId)} added you to ${e.tableName}`,
          description: "Your table settings let them add you right away.",
          tableId: e.tableId,
        }
      : {
          tone: "invite",
          title: `${nameOf(e.fromId)} wants you at ${e.tableName}`,
          description: "Accept or decline under Your tables on Home.",
        };
  if (e.kind === "settle") {
    const who = nameOf(e.otherId);
    const amt = `$${fmt(e.amount)}`;
    const title =
      e.change === "asked"
        ? `${who} asked you to forgive ${amt}`
        : e.change === "declined"
          ? `${who} didn't forgive your ${amt}`
          : `${who} forgave your ${amt}`;
    const description =
      e.change === "asked"
        ? "Forgive or decline it under Friends, Settle-ups."
        : e.change === "declined"
          ? "You can pay it, or ask again in a day."
          : "It's cleared. Nothing left to send.";
    return { tone: "settle", title, description, tableId: e.tableId };
  }
  if (e.kind === "chat") {
    const m = e.message;
    return {
      tone: "chat",
      title: `${nameOf(m.authorId)} in chat`,
      description: m.kind === "text" ? m.text : m.kind === "gif" ? "Sent a GIF" : "Sent a ticket",
      tableId: m.tableId,
    };
  }
  const { bet, from, to } = e;
  const base = { description: bet.question, tableId: bet.tableId, betId: bet.id };
  if (from === null)
    return { ...base, tone: "new", title: `New ticket from ${nameOf(bet.proposerId)}` };
  if (to === "pending") return { ...base, tone: "locked", title: "Picks locked" };
  if (to === "washed") return { ...base, tone: "washed", title: "Ticket washed, wagers returned" };
  if (to === "resolved") {
    const d = bet.deltas?.[me];
    const description = `${bet.winningChoice} · ${bet.question}`;
    if (d !== undefined && d > 0)
      return { ...base, description, tone: "won", title: `You won ${fmt(d)} pts` };
    if (d !== undefined && d < 0)
      return { ...base, description, tone: "lost", title: `You lost ${fmt(-d)} pts` };
    return { ...base, tone: "resolved", title: `Resolved: ${bet.winningChoice}` };
  }
  return null;
}

/** Add newest-first and cap the history length. */
export function pushItem(items: NotificationItem[], item: NotificationItem, cap = HISTORY_CAP) {
  return [item, ...items].slice(0, cap);
}

/**
 * Cursor pagination over the newest-first list. The cursor is the id of the last item
 * already shown, so new items arriving at the top don't shift later pages.
 */
export function pageAfter(
  items: NotificationItem[],
  cursor: string | null,
  size = PAGE_SIZE,
): { items: NotificationItem[]; nextCursor: string | null } {
  const start = cursor === null ? 0 : items.findIndex((i) => i.id === cursor) + 1;
  // Cursor fell off the end of the capped history: nothing older to show.
  if (cursor !== null && start === 0) return { items: [], nextCursor: null };
  const page = items.slice(start, start + size);
  const more = start + size < items.length;
  return { items: page, nextCursor: more ? page[page.length - 1]!.id : null };
}

export const unreadCount = (items: NotificationItem[]) => items.filter((i) => !i.read).length;

export const markAllRead = (items: NotificationItem[]) =>
  items.some((i) => !i.read) ? items.map((i) => (i.read ? i : { ...i, read: true })) : items;

/** Short "time ago" label for the inbox: now, 5m, 2h, 3d. */
export function timeAgo(at: number, now: number) {
  const s = Math.max(0, Math.floor((now - at) / 1000));
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

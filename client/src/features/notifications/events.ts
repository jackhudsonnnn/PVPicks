import { paymentsOf } from "@/features/table/settlements";
import { reconcileSettleChanges } from "@/features/social/settle-changes";
import type { Bet, BetStatus } from "@/features/bets/types";
import type { Message, Table, TableInvite } from "@/features/table/types";

/** Which tickets trigger status toasts: only ones you're in, or every ticket at your tables. */
export type TicketScope = "mine" | "all";

export interface NotificationPrefs {
  /** Master switch — off silences everything. */
  enabled: boolean;
  errors: boolean;
  ticketStatus: boolean;
  ticketScope: TicketScope;
  chats: boolean;
  /** Someone added you to a table, or asked you to join one. */
  invites: boolean;
  /** Tables whose chat toasts are muted. */
  mutedTableIds: string[];
}

export const DEFAULT_PREFS: NotificationPrefs = {
  enabled: true,
  errors: true,
  ticketStatus: true,
  ticketScope: "mine",
  chats: true,
  invites: true,
  mutedTableIds: [],
};

export type NotificationEvent =
  | { kind: "balanceChanged"; otherId: string; before: number; after: number }
  | { kind: "ticket"; bet: Bet; from: BetStatus | null; to: BetStatus }
  | { kind: "chat"; message: Extract<Message, { kind: "text" | "bet" | "gif" }> }
  | { kind: "error"; title: string; description?: string | undefined }
  /** joined: you were added right away; otherwise it's a request waiting on you. */
  | { kind: "invite"; tableId: string; tableName: string; fromId: string; joined: boolean }
  /** Forgive flow on one of your settle-ups: they asked you, declined you, or forgave you. */
  | {
      kind: "settle";
      change: "asked" | "declined" | "forgiven";
      tableId: string;
      otherId: string;
      amount: number;
    };

interface Snapshot {
  bets: Bet[];
  messages: Message[];
  tables?: Table[];
  invites?: TableInvite[];
}

/** Compare two store snapshots and list what changed that a user might care about. */
export function diffEvents(prev: Snapshot, next: Snapshot, me: string): NotificationEvent[] {
  const events: NotificationEvent[] = [];
  for (const change of Object.values(reconcileSettleChanges(prev.messages, next.messages).changes)) {
    if (change.fromId !== me && change.toId !== me) continue;
    const sign = change.fromId === me ? 1 : -1;
    events.push({ kind: "balanceChanged", otherId: change.fromId === me ? change.toId : change.fromId, before: change.before * sign, after: change.after * sign });
  }
  const before = new Map(prev.bets.map((b) => [b.id, b.status]));
  for (const bet of next.bets) {
    const from = before.get(bet.id) ?? null;
    if (from === bet.status) continue;
    // Your own new ticket already gets a "sent" confirmation.
    if (from === null && bet.proposerId === me) continue;
    events.push({ kind: "ticket", bet, from, to: bet.status });
  }
  const seen = new Set(prev.messages.map((m) => m.id));
  for (const m of next.messages) {
    if (seen.has(m.id) || (m.kind !== "text" && m.kind !== "gif")) continue;
    if (m.authorId === me) continue;
    events.push({ kind: "chat", message: m });
  }
  const before2 = new Map(prev.messages.map((m) => [m.id, m]));
  for (const m of next.messages) {
    if (m.kind !== "settlement") continue;
    const old = before2.get(m.id);
    if (!old || old.kind !== "settlement") continue;
    const was = new Map(paymentsOf(old).map((p) => [p.lineUserId, p]));
    for (const p of paymentsOf(m)) {
      const o = was.get(p.lineUserId);
      if (!o) continue;
      const base = { kind: "settle" as const, tableId: m.tableId, amount: p.amount };
      if (p.to === me && p.ask?.status === "open" && o.ask?.status !== "open")
        events.push({ ...base, change: "asked", otherId: p.from });
      if (p.from === me && p.ask?.status === "declined" && o.ask?.status !== "declined")
        events.push({ ...base, change: "declined", otherId: p.to });
      if (p.from === me && p.status === "forgiven" && o.status !== "forgiven")
        events.push({ ...base, change: "forgiven", otherId: p.to });
    }
  }
  const oldInvites = new Set((prev.invites ?? []).map((i) => i.id));
  const tableOf = (id: string) => next.tables?.find((t) => t.id === id);
  for (const i of next.invites ?? []) {
    if (oldInvites.has(i.id) || i.userId !== me) continue;
    const t = tableOf(i.tableId);
    if (t)
      events.push({
        kind: "invite",
        tableId: t.id,
        tableName: t.name,
        fromId: i.fromId,
        joined: false,
      });
  }
  // Added straight in: you're a member now, weren't before, didn't accept a request and aren't the host.
  const wasIn = new Set(
    (prev.tables ?? []).filter((t) => t.memberIds.includes(me)).map((t) => t.id),
  );
  const hadInvite = new Set(
    (prev.invites ?? []).filter((i) => i.userId === me).map((i) => i.tableId),
  );
  if (prev.tables)
    for (const t of next.tables ?? []) {
      if (!t.memberIds.includes(me) || wasIn.has(t.id) || hadInvite.has(t.id)) continue;
      if (t.hostIds.includes(me)) continue;
      events.push({
        kind: "invite",
        tableId: t.id,
        tableName: t.name,
        fromId: t.hostIds[0] ?? t.memberIds[0]!,
        joined: true,
      });
    }
  return events;
}

/** Decide whether an event should become a toast, given the user's preferences. */
export function shouldNotify(
  event: NotificationEvent,
  prefs: NotificationPrefs,
  ctx: { me: string; viewingTableId?: string | undefined; blocked?: string[] | undefined },
): boolean {
  if (!prefs.enabled) return false;
  switch (event.kind) {
    case "error":
      return prefs.errors;
    case "ticket": {
      if (!prefs.ticketStatus) return false;
      if (prefs.ticketScope === "all") return true;
      // "Mine": tickets you're in, plus new tickets so you can join them.
      return event.from === null || event.bet.participants.some((p) => p.userId === ctx.me);
    }
    case "invite":
      return prefs.invites;
    case "settle":
    case "balanceChanged":
      return true;
    case "chat":
      if (!prefs.chats) return false;
      if (prefs.mutedTableIds.includes(event.message.tableId)) return false;
      // Chats from people you blocked are hidden, so they never notify.
      if (ctx.blocked?.includes(event.message.authorId)) return false;
      // You're already looking at that table's chat.
      return event.message.tableId !== ctx.viewingTableId;
  }
}

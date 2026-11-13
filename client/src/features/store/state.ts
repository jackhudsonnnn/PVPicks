import type { Bet, Game } from "@/features/bets/types";
import type { RemainderMode } from "@/features/bets/payout";
import type { Profile } from "@/features/settings/profile";
import type { Follows } from "@/features/social/follows";
import type { InvitePrefs } from "@/features/social/invites";
import type { Blocks } from "@/features/social/safety";
import type { User } from "@/features/social/types";
import type { SettleChange } from "@/features/social/settle-changes";
import type { MemberPermission } from "@/features/table/hosts";
import type { Reactions } from "@/features/table/reactions";
import type { Message, NewMessage, Table, TableInvite } from "@/features/table/types";

/** Everything one signed-in player sees. A server would send this; today it starts from sample data. */
export interface AppState {
  /** Who is signed in. Every "you" rule (your picks, your tables, host = you) reads this. */
  meId: string;
  bets: Bet[];
  messages: Message[];
  tables: Table[];
  /** Games the sports feed knows about (sample games until a real feed exists). */
  games: Game[];
  follows: Follows;
  /** Per-user settings for being added to tables (missing = defaults). */
  invitePrefs: Record<string, InvitePrefs>;
  /** Pending "join my table?" requests. */
  tableInvites: TableInvite[];
  reactions: Reactions;
  users: User[];
  /** Who each person blocked (blocking is one-way but cuts contact both ways). */
  blocks: Blocks;
  /** Makes ids for messages and invites the reducer creates (`idBase` keeps them unique across reloads). */
  idBase: string;
  seq: number;
  settleChanges?: Record<string, SettleChange>;
  settleReviews?: Record<string, SettleChange>;
  debtCaps?: Record<string, number | null>;
}

/**
 * Every change to the state. Actions carry who did it (`actorId`/`userId`) and when (`at`),
 * so the reducer stays pure and a server can replay the same actions.
 */
export type Action =
  | { type: "setDebtCap"; userId: string; cap: number | null }
  | { type: "loadDebtCap"; cap: number | null }
  | { type: "tick"; at: string }
  | { type: "join"; betId: string; userId: string; selection?: string | undefined }
  | { type: "pick"; betId: string; userId: string; selection: string }
  | { type: "propose"; bet: Bet; at: string }
  | { type: "chat"; tableId: string; authorId: string; text: string; at: string }
  | { type: "gif"; tableId: string; authorId: string; gifId: string; at: string }
  | { type: "react"; messageId: string; emoji: string; userId: string }
  /** A person calls a U2Pick result (only the settlers the proposer chose may). */
  | { type: "settle"; betId: string; winningChoice: string | null; actorId: string; at: string }
  /** The sports feed reports a game bet's result. */
  | { type: "gameResult"; betId: string; winningChoice: string | null; at: string }
  | { type: "overrideQuickPick"; betId: string; actorId: string; at: string }
  | { type: "updateProfile"; profile: Profile }
  | { type: "follow"; from: string; to: string; on: boolean }
  | { type: "block"; from: string; to: string; on: boolean }
  | { type: "deleteAccount"; userId: string }
  | { type: "loadBlocks"; userId: string; blocked: string[] }
  | { type: "setInvitePrefs"; userId: string; prefs: InvitePrefs }
  | { type: "respondInvite"; inviteId: string; actorId: string; accept: boolean; at: string }
  | { type: "inviteIn"; table: Table; fromId: string; at: string }
  | { type: "createTable"; table: Table; at: string }
  | { type: "updateTable"; tableId: string; name: string; emoji: string; at: string }
  | { type: "toggleHost"; tableId: string; userId: string; at: string }
  | { type: "setRemainderMode"; tableId: string; mode: RemainderMode; at: string }
  | { type: "setHostsOnlyBets"; tableId: string; on: boolean; at: string }
  | { type: "setU2PickOff"; tableId: string; off: boolean; at: string }
  | { type: "setQuickPicks"; tableId: string; on: boolean; code: string; at: string }
  | { type: "setSharing"; tableId: string; code: string | null; at: string }
  | { type: "subscribe"; tableId: string; code: string; at: string }
  | { type: "unsubscribe"; tableId: string; sourceId: string; at: string }
  | { type: "deleteTable"; tableId: string; at: string }
  | { type: "removeMember"; tableId: string; userId: string; at: string }
  | {
      type: "setPermission";
      tableId: string;
      userId: string;
      perm: MemberPermission;
      on: boolean;
      at: string;
    }
  | { type: "addMembers"; tableId: string; userIds: string[]; at: string }
  | { type: "settlePoints"; tableId: string; at: string }
  | { type: "reviewSettleChange"; actorId: string; otherUserId: string; change: SettleChange }
  | {
      type: "settlePerson";
      otherUserId: string;
      actorId: string;
      step: "sent" | "paid" | "unpaid" | "forgiven" | "ask" | "decline";
      now: number;
      expectedRevision?: string;
    }
  | {
      type: "payment";
      messageId: string;
      lineUserId: string;
      /** sent: payer; paid / forgiven / unpaid (never arrived): the person owed. */
      status: "sent" | "paid" | "forgiven" | "unpaid";
      actorId: string;
    }
  | {
      type: "forgiveAsk";
      messageId: string;
      lineUserId: string;
      /** ask: payer requests; decline: the person owed says no. */
      answer: "ask" | "decline";
      actorId: string;
      now: number;
    };

export type ActionOf<T extends Action["type"]> = Extract<Action, { type: T }>;

/* ---------- Small pure helpers shared by the reducers ---------- */

/** A new id for something the reducer creates; deterministic for a given state. */
export function takeId(state: AppState, prefix: string): [string, AppState] {
  return [`${prefix}_${state.idBase}_${state.seq.toString(36)}`, { ...state, seq: state.seq + 1 }];
}

/** Appends chat/system lines, giving each a fresh id. */
export function post(state: AppState, ...messages: NewMessage[]): AppState {
  let next = state;
  const added: Message[] = [];
  for (const m of messages) {
    const [id, s] = takeId(next, "msg");
    next = s;
    added.push({ ...m, id } as Message);
  }
  return added.length ? { ...next, messages: [...next.messages, ...added] } : state;
}

/** A system line at a table. */
export const systemLine = (tableId: string, text: string, at: string): NewMessage => ({
  tableId,
  kind: "system",
  text,
  at,
});

/** Changes one bet; returns the same state when nothing changed (so screens don't re-render). */
export function updateBet(state: AppState, id: string, fn: (b: Bet) => Bet): AppState {
  let changed = false;
  const bets = state.bets.map((b) => {
    if (b.id !== id) return b;
    const next = fn(b);
    if (next !== b) changed = true;
    return next;
  });
  return changed ? { ...state, bets } : state;
}

export function updateTable(state: AppState, id: string, fn: (t: Table) => Table): AppState {
  return { ...state, tables: state.tables.map((t) => (t.id === id ? fn(t) : t)) };
}

export const findTable = (state: AppState, id: string) => state.tables.find((t) => t.id === id);

/** "You" for the signed-in player, otherwise their username, for chat lines. */
export function nameOf(state: AppState, id: string) {
  if (id === state.meId) return "You";
  return state.users.find((u) => u.id === id)?.username ?? "Someone";
}

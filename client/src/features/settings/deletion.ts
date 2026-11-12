import type { Bet } from "@/features/bets/types";
import type { Message, Table, TableInvite } from "@/features/table/types";
import type { User } from "@/features/social/types";
import type { Follows } from "@/features/social/follows";
import type { Reactions } from "@/features/table/reactions";
import type { Blocks } from "@/features/social/safety";

export const DELETED_NAME = "Deleted user";

interface DeletableState {
  bets: Bet[];
  messages: Message[];
  tables: Table[];
  follows: Follows;
  tableInvites: TableInvite[];
  reactions: Reactions;
  users: User[];
  blocks?: Blocks;
}

/**
 * Deleting an account. The shared ledger stays honest for everyone else: past tickets,
 * results and settle-ups keep their numbers but show "Deleted user". Everything personal goes:
 * profile, chats, reactions, follows, blocks, invites and seats. Picks on still-open tickets are
 * dropped (nothing was in the pot yet); locked tickets finish normally.
 * TODO: run this on the server when real accounts exist.
 */
export function deleteUser<S extends DeletableState>(state: S, userId: string): S {
  const tables = state.tables
    .map((t) => {
      if (!t.memberIds.includes(userId) && !t.hostIds.includes(userId)) return t;
      const memberIds = t.memberIds.filter((m) => m !== userId);
      let hostIds = t.hostIds.filter((h) => h !== userId);
      // A table always keeps a host: the longest-seated member takes over.
      if (hostIds.length === 0 && memberIds[0]) hostIds = [memberIds[0]];
      const { [userId]: _gone, ...limits } = t.limits ?? {};
      return { ...t, memberIds, hostIds, limits };
    })
    .filter((t) => t.memberIds.length > 0);
  const kept = new Set(tables.map((t) => t.id));

  const follows: Follows = {};
  for (const [k, v] of Object.entries(state.follows)) {
    if (k !== userId) follows[k] = v.filter((id) => id !== userId);
  }

  const reactions: Reactions = {};
  for (const [msgId, byEmoji] of Object.entries(state.reactions)) {
    const next: Record<string, string[]> = {};
    for (const [emoji, ids] of Object.entries(byEmoji)) {
      const left = ids.filter((id) => id !== userId);
      if (left.length) next[emoji] = left;
    }
    if (Object.keys(next).length) reactions[msgId] = next;
  }

  const blocks: Blocks = {};
  for (const [k, v] of Object.entries(state.blocks ?? {})) {
    if (k !== userId) blocks[k] = v.filter((id) => id !== userId);
  }

  return {
    ...state,
    users: state.users.map((u) =>
      u.id === userId ? { ...u, username: DELETED_NAME, deleted: true } : u,
    ),
    tables,
    follows,
    reactions,
    blocks,
    tableInvites: state.tableInvites.filter(
      (i) => i.userId !== userId && i.fromId !== userId && kept.has(i.tableId),
    ),
    messages: state.messages.filter(
      (m) =>
        kept.has(m.tableId) && !((m.kind === "text" || m.kind === "gif") && m.authorId === userId),
    ),
    bets: state.bets
      .filter((b) => kept.has(b.tableId))
      .map((b) =>
        b.status === "active" && b.participants.some((p) => p.userId === userId)
          ? { ...b, participants: b.participants.filter((p) => p.userId !== userId) }
          : b,
      ),
  };
}

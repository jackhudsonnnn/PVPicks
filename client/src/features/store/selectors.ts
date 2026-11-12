import { friendsOf } from "@/features/social/follows";
import { isBlocked } from "@/features/social/safety";
import { prefsOf } from "./table-reducer";
import type { AppState } from "./state";

/** Tables you sit at (invites you haven't accepted are separate). */
export const myTables = (state: AppState) =>
  state.tables.filter((t) => t.memberIds.includes(state.meId));

/** Pending requests for you to join someone's table, with the table attached. */
export const myInvites = (state: AppState) =>
  state.tableInvites.flatMap((i) => {
    if (i.userId !== state.meId) return [];
    const table = state.tables.find((t) => t.id === i.tableId);
    return table ? [{ ...i, table }] : [];
  });

export const myFriendIds = (state: AppState) => friendsOf(state.follows, state.meId);

/** People you blocked. */
export const myBlocks = (state: AppState) => state.blocks[state.meId] ?? [];

/** People you can see in people lists: not deleted, not blocked either way. */
export const visiblePeople = (state: AppState) =>
  state.users.filter(
    (u) => u.id !== state.meId && !u.deleted && !isBlocked(state.blocks, state.meId, u.id),
  );

export const invitePrefsOf = (state: AppState, id: string) => prefsOf(state, id);

export const gameById = (state: AppState, id: string | undefined) =>
  id ? state.games.find((g) => g.id === id) : undefined;

// TODO(backend-logic): authoritative rules here (permissions, lock, payout, settle, debt-cap, blocks) must run
// server-side; the client reducer should only apply server-confirmed events/optimistic updates.
import { deleteUser } from "@/features/settings/deletion";
import { setFollow } from "@/features/social/follows";
import { isBlocked, setBlock } from "@/features/social/safety";
import { toggleReaction } from "@/features/table/reactions";
import type { TableInvite } from "@/features/table/types";
import { allowedAt } from "./bets-reducer";
import { post, type ActionOf, type AppState } from "./state";

export type SocialAction = ActionOf<
  | "chat"
  | "gif"
  | "react"
  | "updateProfile"
  | "follow"
  | "block"
  | "deleteAccount"
  | "loadBlocks"
  | "setInvitePrefs"
>;

/** Chat, people and your account. */
export function socialReducer(state: AppState, action: SocialAction): AppState {
  switch (action.type) {
    case "chat":
      if (!allowedAt(state, action.tableId, action.authorId, "text")) return state;
      return post(state, {
        tableId: action.tableId,
        kind: "text",
        authorId: action.authorId,
        text: action.text,
        at: action.at,
      });
    case "gif":
      if (!allowedAt(state, action.tableId, action.authorId, "gifs")) return state;
      return post(state, {
        tableId: action.tableId,
        kind: "gif",
        authorId: action.authorId,
        gifId: action.gifId,
        at: action.at,
      });
    case "react": {
      const m = state.messages.find((x) => x.id === action.messageId);
      if (!m || !allowedAt(state, m.tableId, action.userId, "react")) return state;
      return {
        ...state,
        reactions: toggleReaction(state.reactions, action.messageId, action.emoji, action.userId),
      };
    }
    case "updateProfile":
      return {
        ...state,
        users: state.users.map((u) => (u.id === state.meId ? { ...u, ...action.profile } : u)),
      };
    case "follow":
      if (action.on && isBlocked(state.blocks, action.from, action.to)) return state;
      return { ...state, follows: setFollow(state.follows, action.from, action.to, action.on) };
    case "block": {
      const { from, to, on } = action;
      const blocks = setBlock(state.blocks, from, to, on);
      if (blocks === state.blocks) return state;
      if (!on) return { ...state, blocks };
      // Blocking removes follows both ways and any pending table requests between you.
      const follows = setFollow(setFollow(state.follows, from, to, false), to, from, false);
      const pair = (i: TableInvite) =>
        (i.userId === from && i.fromId === to) || (i.userId === to && i.fromId === from);
      return {
        ...state,
        blocks,
        follows,
        tableInvites: state.tableInvites.filter((i) => !pair(i)),
      };
    }
    case "deleteAccount":
      return deleteUser(state, action.userId);
    case "loadBlocks":
      return { ...state, blocks: { ...state.blocks, [action.userId]: action.blocked } };
    case "setInvitePrefs":
      return { ...state, invitePrefs: { ...state.invitePrefs, [action.userId]: action.prefs } };
  }
}

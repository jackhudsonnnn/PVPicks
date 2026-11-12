// TODO(backend-logic): authoritative rules here (permissions, lock, payout, settle, debt-cap, blocks) must run
// server-side; the client reducer should only apply server-confirmed events/optimistic updates.
import { REMAINDER_MODES } from "@/features/bets/payout";
import { DEFAULT_INVITE_PREFS, hasPendingInvite, inviteOutcome } from "@/features/social/invites";
import { isBlocked } from "@/features/social/safety";
import {
  canAddMember,
  canDeleteTable,
  canRemoveMember,
  canSetPermissions,
  canToggleHost,
  clearLimits,
  hasPermission,
  isHost,
  removalBlockedByBets,
  setPermission,
} from "@/features/table/hosts";
import { subscribeCheck } from "@/features/table/quick-picks";
import type { Table, TableInvite } from "@/features/table/types";
import { postCopy } from "./bets-reducer";
import {
  findTable,
  nameOf,
  post,
  systemLine,
  takeId,
  updateTable,
  type ActionOf,
  type AppState,
} from "./state";

export const prefsOf = (state: AppState, id: string) =>
  state.invitePrefs[id] ?? DEFAULT_INVITE_PREFS;

/** Removes `sourceId` from every table subscribed to it (new code, sharing off). */
function dropSubscribers(state: AppState, sourceId: string): AppState {
  return {
    ...state,
    tables: state.tables.map((t) =>
      (t.subscribedTo ?? []).includes(sourceId)
        ? { ...t, subscribedTo: t.subscribedTo!.filter((x) => x !== sourceId) }
        : t,
    ),
  };
}

/** Pending requests created in one go (each gets its own id). */
function addInvites(state: AppState, invites: Omit<TableInvite, "id">[]): AppState {
  let next = state;
  const added: TableInvite[] = [];
  for (const inv of invites) {
    const [id, s] = takeId(next, "ti");
    next = s;
    added.push({ ...inv, id });
  }
  return { ...next, tableInvites: [...next.tableInvites, ...added] };
}

export type MembershipAction = ActionOf<
  "createTable" | "addMembers" | "inviteIn" | "respondInvite"
>;

export function membershipReducer(state: AppState, action: MembershipAction): AppState {
  const me = state.meId;
  switch (action.type) {
    case "createTable":
      return post(
        { ...state, tables: [action.table, ...state.tables] },
        systemLine(action.table.id, "You created the table", action.at),
      );
    case "addMembers": {
      // You (a host) add players. Each person's table-invite settings decide: join now,
      // get a request, or nothing. One chat line sums it up so "Add all" doesn't flood the chat.
      const table = findTable(state, action.tableId);
      if (!table || !isHost(table, me)) return state;
      const added: string[] = [];
      const invited: string[] = [];
      for (const id of new Set(action.userIds)) {
        if (!canAddMember(table, me, id) || hasPendingInvite(state.tableInvites, table.id, id))
          continue;
        if (isBlocked(state.blocks, me, id)) continue;
        const outcome = inviteOutcome(state.follows, prefsOf(state, id), id, me);
        if (outcome === "add") added.push(id);
        else if (outcome === "request") invited.push(id);
      }
      if (added.length === 0 && invited.length === 0) return state;
      const names = (ids: string[]) =>
        ids.length <= 2
          ? ids.map((id) => nameOf(state, id)).join(" and ")
          : `${ids.length} players`;
      const text = [
        added.length ? `You added ${names(added)}` : "",
        invited.length ? `${added.length ? "invited" : "You invited"} ${names(invited)}` : "",
      ]
        .filter(Boolean)
        .join(", ");
      const withMembers = updateTable(state, table.id, (t) => ({
        ...t,
        memberIds: [...t.memberIds, ...added],
      }));
      return post(
        addInvites(
          withMembers,
          invited.map((userId) => ({ tableId: table.id, fromId: me, userId })),
        ),
        systemLine(table.id, text, action.at),
      );
    }
    case "inviteIn": {
      // Someone else's table adds you; your table-invite settings decide what happens.
      const { table, fromId } = action;
      if (findTable(state, table.id) || isBlocked(state.blocks, me, fromId)) return state;
      const outcome = inviteOutcome(state.follows, prefsOf(state, me), me, fromId);
      if (outcome === "blocked") return state;
      if (outcome === "request")
        return addInvites({ ...state, tables: [...state.tables, table] }, [
          { tableId: table.id, fromId, userId: me },
        ]);
      return post(
        { ...state, tables: [{ ...table, memberIds: [...table.memberIds, me] }, ...state.tables] },
        systemLine(table.id, `${nameOf(state, fromId)} added you to the table`, action.at),
      );
    }
    case "respondInvite": {
      const inv = state.tableInvites.find((i) => i.id === action.inviteId);
      if (!inv || inv.userId !== action.actorId) return state;
      const rest = { ...state, tableInvites: state.tableInvites.filter((i) => i.id !== inv.id) };
      const table = findTable(state, inv.tableId);
      if (!action.accept || !table || table.memberIds.includes(inv.userId)) return rest;
      return post(
        updateTable(rest, table.id, (t) => ({ ...t, memberIds: [...t.memberIds, inv.userId] })),
        systemLine(table.id, `${nameOf(state, inv.userId)} joined the table`, action.at),
      );
    }
  }
}

export type HostAction = ActionOf<
  | "updateTable"
  | "toggleHost"
  | "setRemainderMode"
  | "setHostsOnlyBets"
  | "setU2PickOff"
  | "setQuickPicks"
  | "setSharing"
  | "subscribe"
  | "unsubscribe"
  | "removeMember"
  | "deleteTable"
  | "setPermission"
>;

/** Host-only table changes by you; the rules come from hosts.ts and quick-picks.ts. */
export function hostReducer(state: AppState, action: HostAction): AppState {
  const me = state.meId;
  const table = findTable(state, action.tableId);
  if (!table || !isHost(table, me)) return state;
  const save = (next: Table, text: string) =>
    post(
      updateTable(state, table.id, () => next),
      ...(text ? [systemLine(table.id, text, action.at)] : []),
    );
  const name = (id: string) => nameOf(state, id);

  switch (action.type) {
    case "updateTable": {
      const n = action.name.trim();
      if (!n) return state;
      return save(
        { ...table, name: n, emoji: action.emoji },
        `You renamed the table to ${action.emoji} ${n}`,
      );
    }
    case "setRemainderMode": {
      if ((table.remainderMode ?? "host") === action.mode) return state;
      // Locked once any bet is taking picks; pending bets keep the rule they locked with.
      if (hasActiveBet(state, table.id)) return state;
      const label = REMAINDER_MODES.find((m) => m.id === action.mode)!.label;
      return save(
        { ...table, remainderMode: action.mode },
        `You set leftover cents to go to: ${label}`,
      );
    }
    case "setHostsOnlyBets":
      if (!!table.hostsOnlyBets === action.on) return state;
      return save(
        { ...table, hostsOnlyBets: action.on },
        action.on ? "Only hosts can start new bets now" : "Everyone can start bets again",
      );
    case "setU2PickOff":
      if (!!table.u2pickOff === action.off) return state;
      return save(
        { ...table, u2pickOff: action.off },
        action.off ? "U2Pick custom bets are turned off" : "U2Pick custom bets are back on",
      );
    case "setQuickPicks": {
      if (!!table.quickPicksOn === action.on) return state;
      // Sharing is always on with Quick Picks: turning it on hands out a share code.
      if (action.on)
        return save(
          { ...table, quickPicksOn: true, shareCode: table.shareCode ?? action.code },
          "Quick Picks are on. This table shares its bets",
        );
      // Off: stop sharing (subscribers are cut off) and drop this table's subscriptions.
      const cut = dropSubscribers(state, table.id);
      return post(
        updateTable(cut, table.id, (t) => ({
          ...t,
          quickPicksOn: false,
          shareCode: undefined,
          subscribedTo: [],
        })),
        systemLine(
          table.id,
          "Quick Picks are off. Sharing stopped and subscriptions were removed",
          action.at,
        ),
      );
    }
    case "setSharing": {
      if (!table.quickPicksOn) return state;
      // A new code (or turning sharing off) cuts off everyone using the old one.
      const text = action.code
        ? table.shareCode
          ? "New Quick Picks code. Tables using the old code were cut off"
          : "This table now shares its bets through Quick Picks"
        : "This table stopped sharing its bets";
      return post(
        updateTable(dropSubscribers(state, table.id), table.id, (t) => ({
          ...t,
          shareCode: action.code ?? undefined,
        })),
        systemLine(table.id, text, action.at),
      );
    }
    case "subscribe": {
      const check = subscribeCheck(state.tables, table, me, action.code);
      if ("error" in check) return state;
      const { source } = check;
      const next = { ...table, subscribedTo: [...(table.subscribedTo ?? []), source.id] };
      let withCopies = post(
        updateTable(state, table.id, () => next),
        systemLine(
          table.id,
          `Subscribed to ${source.emoji} ${source.name}. Their new bets post here automatically`,
          action.at,
        ),
      );
      // Bets still open at the source come over right away.
      for (const b of state.bets.filter((x) => x.tableId === source.id))
        withCopies = postCopy(withCopies, b, source, next, action.at);
      return withCopies;
    }
    case "unsubscribe": {
      if (!(table.subscribedTo ?? []).includes(action.sourceId)) return state;
      const src = findTable(state, action.sourceId)?.name ?? "a table";
      return save(
        { ...table, subscribedTo: table.subscribedTo!.filter((x) => x !== action.sourceId) },
        `Unsubscribed from ${src}. Tickets already here stay`,
      );
    }
    case "toggleHost": {
      if (!canToggleHost(table, me, action.userId)) return state;
      const was = isHost(table, action.userId);
      // New hosts start with every permission; old limits don't come back if they step down.
      return save(
        {
          ...clearLimits(table, action.userId),
          hostIds: was
            ? table.hostIds.filter((h) => h !== action.userId)
            : [...table.hostIds, action.userId],
        },
        was ? `${name(action.userId)} is no longer a host` : `${name(action.userId)} is now a host`,
      );
    }
    case "removeMember":
      if (!canRemoveMember(table, me, action.userId) || removalBlockedByBets(table, state.bets))
        return state;
      return save(
        {
          ...clearLimits(table, action.userId),
          memberIds: table.memberIds.filter((m) => m !== action.userId),
          hostIds: table.hostIds.filter((h) => h !== action.userId),
        },
        "A member was removed",
      );
    case "deleteTable": {
      if (!canDeleteTable(table, me, state.bets)) return state;
      const gone = table.id;
      return {
        ...state,
        tables: state.tables
          .filter((t) => t.id !== gone)
          .map((t) =>
            t.subscribedTo?.includes(gone)
              ? { ...t, subscribedTo: t.subscribedTo.filter((id) => id !== gone) }
              : t,
          ),
        bets: state.bets.filter((b) => b.tableId !== gone),
        messages: state.messages.filter((m) => m.tableId !== gone),
        tableInvites: state.tableInvites.filter((i) => i.tableId !== gone),
      };
    }
    case "setPermission":
      // Quiet change: no chat line, so limiting someone doesn't call them out in front of the table.
      if (!canSetPermissions(table, me, action.userId)) return state;
      if (hasPermission(table, action.userId, action.perm) === action.on) return state;
      return save(setPermission(table, action.userId, action.perm, action.on), "");
  }
}

/** True while any bet at the table is still taking picks. */
function hasActiveBet(state: AppState, tableId: string) {
  return state.bets.some((b) => b.tableId === tableId && b.status === "active");
}

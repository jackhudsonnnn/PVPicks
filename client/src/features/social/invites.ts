import type { Table, TableInvite } from "@/features/table/types";
import { followersOf, friendsOf, isFollowing, isFriend, type Follows } from "./follows";

/** What happens when someone adds you to a table: join right away, get a request, or nothing. */
export type InviteMode = "open" | "request" | "closed";

/** Your settings for being added to tables, split by how you know the host. */
export interface InvitePrefs {
  /** Hosts you follow who follow you back. */
  friends: InviteMode;
  /** Hosts you follow who don't follow you back. */
  following: InviteMode;
}

export const DEFAULT_INVITE_PREFS: InvitePrefs = { friends: "open", following: "request" };

export const INVITE_MODES: { id: InviteMode; label: string; description: string }[] = [
  { id: "open", label: "Open", description: "They can add you right away." },
  { id: "request", label: "Request", description: "You get a request to accept or decline." },
  { id: "closed", label: "Closed", description: "They can't add you." },
];

/**
 * How the invitee knows the host. Only friends and people the invitee follows can add them,
 * so a host can add their friends and their followers, never strangers.
 */
function inviteRelation(f: Follows, invitee: string, host: string) {
  if (isFriend(f, invitee, host)) return "friends" as const;
  if (isFollowing(f, invitee, host)) return "following" as const;
  return null;
}

export type InviteOutcome = "add" | "request" | "blocked";

export function inviteOutcome(
  f: Follows,
  prefs: InvitePrefs,
  invitee: string,
  host: string,
): InviteOutcome {
  const rel = inviteRelation(f, invitee, host);
  if (!rel) return "blocked";
  const mode = prefs[rel];
  return mode === "open" ? "add" : mode === "request" ? "request" : "blocked";
}

/** Pending requests for `userId` at `tableId`. */
export const hasPendingInvite = (invites: TableInvite[], tableId: string, userId: string) =>
  invites.some((i) => i.tableId === tableId && i.userId === userId);

/**
 * People a host can add to a table, grouped for the "Add all" buttons. Skips current members,
 * anyone with a pending request, and anyone whose settings block this host.
 */
export function addablePlayers(
  f: Follows,
  prefsOf: (id: string) => InvitePrefs,
  table: Table,
  invites: TableInvite[],
  host: string,
) {
  const ok = (id: string) =>
    !table.memberIds.includes(id) &&
    !hasPendingInvite(invites, table.id, id) &&
    inviteOutcome(f, prefsOf(id), id, host) !== "blocked";
  return {
    friends: friendsOf(f, host).filter(ok),
    followers: followersOf(f, host).filter(ok),
  };
}

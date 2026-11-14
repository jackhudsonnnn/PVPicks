import type { Relationships } from "./api";

/** Rules for people lists and search, matching the server. */

export type PeopleTab = "friends" | "following" | "followers" | "blocked";

/** Usernames are 1–15 lowercase letters, digits or underscores; search uses the same rule. */
export function searchTerm(text: string): string | null {
  const term = text.trim().toLowerCase();
  return /^[a-z0-9_]{1,15}$/.test(term) ? term : null;
}

export function peopleIn(tab: PeopleTab, relationships: Relationships): string[] {
  const { followingIds, followerIds, blockedIds } = relationships;
  switch (tab) {
    case "friends":
      return followingIds.filter((id) => followerIds.includes(id));
    case "following":
      return followingIds;
    case "followers":
      return followerIds;
    case "blocked":
      return blockedIds;
  }
}

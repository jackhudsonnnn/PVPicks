import type { Relationships } from "./api";

/** Rules for people lists and search, matching the server. */

type PeopleTab = "friends" | "requests" | "following" | "blocked";

/** Usernames are 1–15 lowercase letters, digits or underscores; search uses the same rule. */
export function searchTerm(text: string): string | null {
  const term = text.trim().toLowerCase();
  return /^[a-z0-9_]{1,15}$/.test(term) ? term : null;
}

export function peopleIn(tab: PeopleTab, relationships: Relationships): string[] {
  const { followingIds, followerIds, friendIds, blockedIds } = relationships;
  switch (tab) {
    case "friends":
      return friendIds;
    case "requests":
      // Followers you haven't followed back: following them makes you friends.
      return followerIds.filter((id) => !followingIds.includes(id));
    case "following":
      return followingIds;
    case "blocked":
      return blockedIds;
  }
}

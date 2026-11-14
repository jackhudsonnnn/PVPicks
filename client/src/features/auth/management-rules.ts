import type { BackendTable } from "./backend-api";
import type { Relationships } from "./social-api";

/** Pure rules for tables, people and preferences, matching what the server accepts. */

export type PeopleTab = "friends" | "following" | "followers" | "blocked";

export const TABLE_NAME_LIMIT = 40;

/** Why a table name can't be used, or null. Matches the server: 1–40 characters after trimming. */
export function tableNameProblem(name: string): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Give the table a name.";
  if ([...trimmed].length > TABLE_NAME_LIMIT)
    return `Keep the name to ${TABLE_NAME_LIMIT} characters or fewer.`;
  return null;
}

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

/** People who follow you, are not members yet and are not blocked: the ones a host can try to add. */
export function addableFollowers(
  table: BackendTable,
  followerIds: string[],
  blockedIds: string[],
): string[] {
  return followerIds.filter((id) => !table.memberIds.includes(id) && !blockedIds.includes(id));
}

/** Whole points as cents, or why the text isn't a valid cap (1 to 21,474,836 whole points). */
export function parseCap(text: string): { cents: number } | { problem: string } {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) return { problem: "Enter a whole number of points." };
  const points = Number(trimmed);
  if (points < 1 || points > 21474836) return { problem: "Use 1 to 21,474,836 points." };
  return { cents: points * 100 };
}

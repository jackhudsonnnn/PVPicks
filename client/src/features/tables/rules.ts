import type { Table } from "./api";

/** Table rules that match what the server accepts. */

/** Names fit a table card; the server and database enforce the same limit. */
export const TABLE_NAME_LIMIT = 14;

/** Why a table name can't be used, or null. Matches the server: 1–14 characters after trimming. */
export function tableNameProblem(name: string): string | null {
  const trimmed = name.trim();
  if (!trimmed) return "Give the table a name.";
  if ([...trimmed].length > TABLE_NAME_LIMIT)
    return `Keep the name to ${TABLE_NAME_LIMIT} characters or fewer.`;
  return null;
}

/** People who follow you, are not members yet and are not blocked: the ones a host can try to add. */
export function addableFollowers(
  table: Table,
  followerIds: string[],
  blockedIds: string[],
): string[] {
  return followerIds.filter((id) => !table.memberIds.includes(id) && !blockedIds.includes(id));
}

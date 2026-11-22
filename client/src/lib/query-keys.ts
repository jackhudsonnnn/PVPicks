/**
 * Every query for the signed-in user lives under ["backend-tables", userId, ...], so signing out clears all of
 * it and realtime invalidations can target one user's data.
 */
export const tablesKey = ["backend-tables"] as const;

/** The signed-in account (`GET /me`); refetched when your profile changes. */
export const sessionKey = ["backend-session"] as const;

export function userKey(userId: string, ...parts: string[]) {
  return [...tablesKey, userId, ...parts];
}

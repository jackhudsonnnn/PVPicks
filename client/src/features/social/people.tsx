import { createContext, useCallback, useContext, useMemo, type ReactNode } from "react";
import type { User } from "./types";

/**
 * Who's who: looks up a person by id for names and avatars. The player store provides the
 * people in your world; the admin view provides the whole sample directory.
 */
const PeopleContext = createContext<Map<string, User> | null>(null);

export function PeopleProvider({ users, children }: { users: User[]; children: ReactNode }) {
  const byId = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);
  return <PeopleContext.Provider value={byId}>{children}</PeopleContext.Provider>;
}

/** Shown for ids nobody knows (e.g. a person who left before the data loaded). */
const unknownUser = (id: string): User => ({
  id,
  username: "unknown",
  hue: 1,
});

/** Returns a lookup `(id) => User`; unknown ids get a placeholder instead of crashing. */
export function useGetUser() {
  const byId = useContext(PeopleContext);
  if (!byId) throw new Error("useGetUser must be used inside PeopleProvider");
  return useCallback((id: string) => byId.get(id) ?? unknownUser(id), [byId]);
}

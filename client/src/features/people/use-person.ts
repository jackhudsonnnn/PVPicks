import { useQuery } from "@tanstack/react-query";
import type { Hue } from "@/features/preferences";
import { getProfile, type User } from "./api";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";

export interface Person {
  name: string;
  hue: Hue | null;
  deleted: boolean;
}

/**
 * A person's display name and color, from the same profile cache `PersonName` uses (`[...queryKey, "profile", id]`).
 * The viewer is always "You".
 */
export function usePerson(
  id: string,
  user: User,
  expire: () => void,
  queryKey: readonly string[],
): Person | null {
  const query = useQuery({
    queryKey: [...queryKey, "profile", id],
    queryFn: ({ signal }) => getProfile(id, signal),
    retry: false,
    // Live profile.changed events invalidate this, so it never needs to refetch on its own.
    staleTime: Infinity,
    enabled: id !== user.id,
  });
  useExpireOnUnauthorized([query.error], expire);
  // The schema limits hues to 1-5.
  if (id === user.id) return { name: "You", hue: user.hue as Hue, deleted: false };
  if (!query.data) return null;
  return "username" in query.data
    ? { name: query.data.username, hue: query.data.hue as Hue, deleted: false }
    : { name: query.data.displayName, hue: null, deleted: true };
}

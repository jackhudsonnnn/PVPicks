import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import type { Hue } from "@/features/settings/profile";
import { BackendError, getProfile, type BackendUser } from "./backend-api";

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
  user: BackendUser,
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
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  // The schema limits hues to 1-5.
  if (id === user.id) return { name: "You", hue: user.hue as Hue, deleted: false };
  if (!query.data) return null;
  return "username" in query.data
    ? { name: query.data.username, hue: query.data.hue as Hue, deleted: false }
    : { name: query.data.displayName, hue: null, deleted: true };
}

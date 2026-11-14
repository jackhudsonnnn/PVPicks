import { useQuery } from "@tanstack/react-query";
import { userKey } from "./query-keys";
import { getRelationships, listInvites } from "./social-api";

export function useRelationships(userId: string) {
  return useQuery({
    queryKey: userKey(userId, "relationships"),
    queryFn: ({ signal }) => getRelationships(signal),
    retry: false,
  });
}

export function useInvites(userId: string) {
  return useQuery({
    queryKey: userKey(userId, "invites"),
    queryFn: ({ signal }) => listInvites(signal),
    retry: false,
  });
}

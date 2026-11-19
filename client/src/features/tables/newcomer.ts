import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { useHasTickets } from "@/features/bets";
import { useRelationships } from "@/features/social";
import { listTables } from "./api";

/**
 * Someone with no friends, no tables and no tickets yet, who gets "Add friends first" instead of New table and
 * All tickets. False until all three are known, so nobody is held up while they load or after an error.
 */
export function useNewcomer(userId: string): boolean {
  const relationships = useRelationships(userId);
  const tables = useQuery({
    queryKey: userKey(userId, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const hasTickets = useHasTickets(userId);
  return relationships.data?.friendIds.length === 0 && tables.data?.length === 0 && !hasTickets;
}

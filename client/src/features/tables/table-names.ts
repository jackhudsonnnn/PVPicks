import { useQuery } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { listArchives, listTables } from "./api";

export interface TableName {
  emoji: string;
  name: string;
  /** Still open, so it can be linked; deleted tables are named for 90 days but not opened from here. */
  current: boolean;
}

/**
 * Names for tables you're at or recently deleted, for receipts that point at a table. Shares the table list and
 * archive queries with Home, so it costs no extra requests there. Returns null for tables you've left.
 */
export function useTableNames(userId: string): (tableId: string) => TableName | null {
  const tables = useQuery({
    queryKey: userKey(userId, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const archives = useQuery({
    queryKey: userKey(userId, "archives"),
    queryFn: ({ signal }) => listArchives(signal),
    retry: false,
  });
  return (tableId) => {
    const table = tables.data?.find((t) => t.id === tableId);
    if (table) return { emoji: table.emoji, name: table.name, current: true };
    const archive = archives.data?.find((a) => a.id === tableId);
    return archive ? { emoji: archive.emoji, name: archive.name, current: false } : null;
  };
}

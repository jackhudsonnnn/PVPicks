import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/empty-state";
import { backendErrorMessage } from "@/lib/api-client";
import { listTables } from "@/features/tables/api";
import type { User } from "@/features/people";
import { tablesKey } from "@/lib/query-keys";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";

/** Your tables on the home page. */
export function TableList({ user, expire }: { user: User; expire: () => void }) {
  const query = useQuery({
    queryKey: [...tablesKey, user.id, "list"],
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  useExpireOnUnauthorized([query.error], expire);
  return (
    <section className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h1 className="font-display text-3xl font-bold uppercase">Your tables</h1>
        <Button
          variant="outline"
          size="sm"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
        >
          <RefreshCw /> Refresh
        </Button>
      </div>
      {query.isPending ? (
        <p>Loading tables…</p>
      ) : query.isError ? (
        <p role="alert">{backendErrorMessage(query.error)}</p>
      ) : query.data.length === 0 ? (
        <EmptyState boxed>No tables yet.</EmptyState>
      ) : (
        <ul className="space-y-2">
          {query.data.map((table) => (
            <li key={table.id}>
              <Link
                to="/tables/$tableId"
                params={{ tableId: table.id }}
                className="flex items-center gap-3 rounded-lg border bg-surface p-4 hover:border-primary"
              >
                <span className="text-2xl">{table.emoji}</span>
                <span>
                  <span className="block font-semibold">{table.name}</span>
                  <span className="text-sm text-muted-foreground">
                    {table.memberIds.length} {table.memberIds.length === 1 ? "member" : "members"}
                    {table.hostIds.includes(user.id) ? " · Host" : ""}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

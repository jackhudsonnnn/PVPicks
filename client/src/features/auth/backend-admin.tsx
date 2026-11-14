import { useQuery } from "@tanstack/react-query";
import { LogOut, RefreshCw } from "lucide-react";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/logo";
import { backendErrorMessage, read, request, type BackendUser } from "./backend-api";
import { userKey } from "./query-keys";
import { timeLabel } from "@/lib/time";

const status = z.enum(["UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN"]);
const healthSchema = z.object({
  checkedAt: z.string().datetime({ offset: true }),
  postgres: status,
  redis: status,
  kafka: z.literal("NOT_CHECKED"),
});

async function getAdminHealth(signal?: AbortSignal) {
  return read(await request("/admin/health", signal ? { signal } : undefined), healthSchema);
}

/**
 * The admin view. Admin accounts cannot use player APIs (tables, bets, chat, friends), so they see admin tools
 * only. Today that is dependency health; user audit, moderation and reports are not implemented on the server.
 */
export function AdminWorkspace({
  user,
  logout,
}: {
  user: BackendUser;
  logout: () => Promise<void>;
}) {
  const health = useQuery({
    queryKey: userKey(user.id, "admin-health"),
    queryFn: ({ signal }) => getAdminHealth(signal),
    retry: false,
  });
  return (
    <div className="min-h-dvh bg-field">
      <header className="glass-bar flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 md:px-8">
        <div className="flex items-center gap-3">
          <Logo />
          <span className="rounded bg-muted px-2 py-0.5 font-mono text-xs font-bold">Admin</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm">{user.username}</span>
          <Button variant="outline" size="sm" onClick={() => void logout()}>
            <LogOut /> Sign out
          </Button>
        </div>
      </header>
      <main className="mx-auto max-w-4xl space-y-6 px-4 py-8 md:px-8">
        <p className="rounded-lg border bg-surface p-3 text-sm text-muted-foreground">
          Admin accounts manage the platform and can't join tables, bet or chat. User audit,
          moderation and reports are not available yet.
        </p>
        <section className="space-y-3" aria-labelledby="health-heading">
          <div className="flex items-center justify-between gap-3">
            <h1 id="health-heading" className="font-display text-3xl font-bold uppercase">
              Site health
            </h1>
            <Button
              variant="outline"
              size="sm"
              disabled={health.isFetching}
              onClick={() => void health.refetch()}
            >
              <RefreshCw /> Refresh
            </Button>
          </div>
          {health.isPending ? (
            <p>Checking…</p>
          ) : health.isError ? (
            <p role="alert">{backendErrorMessage(health.error)}</p>
          ) : (
            <>
              <dl className="grid gap-3 sm:grid-cols-3">
                {(
                  [
                    ["PostgreSQL", health.data.postgres],
                    ["Redis", health.data.redis],
                    ["Kafka", "Not checked"],
                  ] as const
                ).map(([name, value]) => (
                  <div key={name} className="rounded-lg border bg-surface p-4">
                    <dt className="text-sm text-muted-foreground">{name}</dt>
                    <dd className={value === "UP" ? "font-semibold text-primary" : "font-semibold"}>
                      {value === "UP" ? "Up" : value}
                    </dd>
                  </div>
                ))}
              </dl>
              <p className="text-xs text-muted-foreground">
                Checked {timeLabel(health.data.checkedAt)}.
              </p>
            </>
          )}
        </section>
      </main>
    </div>
  );
}

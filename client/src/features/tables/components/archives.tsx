import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { dateTimeLabel } from "@/lib/time";
import { backendErrorMessage, type BackendUser } from "./backend-api";
import { BackendChat } from "./backend-chat";
import { userKey } from "./query-keys";
import { listArchives } from "./tables-api";

function useArchives(userId: string) {
  return useQuery({
    queryKey: userKey(userId, "archives"),
    queryFn: ({ signal }) => listArchives(signal),
    retry: false,
  });
}

/** Deleted tables whose chat you can still read; hidden when there are none. */
export function ArchivedTables({ user }: { user: BackendUser }) {
  const archives = useArchives(user.id);
  if (!archives.data?.length) return null;
  return (
    <section className="space-y-3" aria-labelledby="archives-heading">
      <h2 id="archives-heading" className="font-display text-2xl font-bold uppercase">
        Deleted tables
      </h2>
      <p className="text-sm text-muted-foreground">
        Their chat stays readable for 90 days after deletion.
      </p>
      <ul className="space-y-2">
        {archives.data.map((archive) => (
          <li key={archive.id}>
            <Link
              to="/archives/$tableId"
              params={{ tableId: archive.id }}
              className="flex items-center gap-3 rounded-lg border bg-surface p-4 hover:border-primary"
            >
              <span className="text-2xl">{archive.emoji}</span>
              <span>
                <span className="block font-semibold">{archive.name}</span>
                <span className="text-sm text-muted-foreground">
                  Readable until {dateTimeLabel(archive.expiresAt)}
                </span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** A deleted table's chat, read-only. */
export function ArchivedTable({
  user,
  tableId,
  expire,
}: {
  user: BackendUser;
  tableId: string;
  expire: () => void;
}) {
  const archives = useArchives(user.id);
  const archive = archives.data?.find((item) => item.id === tableId);
  return (
    <section className="space-y-4">
      <Link to="/" className="text-sm text-primary underline">
        Back to tables
      </Link>
      {archives.isPending ? (
        <p>Loading…</p>
      ) : archives.isError ? (
        <p role="alert">{backendErrorMessage(archives.error)}</p>
      ) : !archive ? (
        <p role="alert">This archive is unavailable or has expired.</p>
      ) : (
        <>
          <div className="space-y-2 rounded-lg border bg-surface p-5">
            <h1 className="font-display text-3xl font-bold uppercase">
              {archive.emoji} {archive.name}
            </h1>
            <p className="text-sm text-muted-foreground">
              Deleted {dateTimeLabel(archive.archivedAt)}. The chat is read-only until{" "}
              {dateTimeLabel(archive.expiresAt)}.
            </p>
          </div>
          <BackendChat
            user={user}
            table={{ id: archive.id, disabledPermissions: {} }}
            expire={expire}
            betQuestion={() => "details are no longer available"}
            onRead={() => {}}
            readOnly
          />
        </>
      )}
    </section>
  );
}

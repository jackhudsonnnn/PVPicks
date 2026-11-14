import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { LogIn, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { EmptyState } from "@/components/empty-state";
import {
  backendErrorMessage,
  BackendError,
  currentUser,
  listTables,
  signIn,
  signOut,
  type BackendUser,
} from "./backend-api";
import { AdminWorkspace } from "./backend-admin";
import { ArchivedTable, ArchivedTables } from "./backend-archives";
import { BackendFriends } from "./backend-friends";
import { BackendGuide } from "./backend-guide";
import { CreateTable, Invitations } from "./backend-home";
import { BackendPreferences } from "./backend-preferences";
import { AppShell } from "./backend-shell";
import { TablePage } from "./backend-table-page";
import { TicketsPage } from "./backend-tickets";
import { RealtimeProvider, useRealtimeFeed } from "./realtime";
import { personalFeed } from "./realtime-connection";

const sessionKey = ["backend-session"] as const;
const tablesKey = ["backend-tables"] as const;

export function BackendApp() {
  const queryClient = useQueryClient();
  const [notice, setNotice] = useState<string | null>(null);
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => currentUser(signal),
    retry: false,
  });

  async function clearSession(message?: string) {
    await queryClient.cancelQueries({ queryKey: sessionKey });
    queryClient.setQueryData(sessionKey, null);
    setNotice(message ?? null);
  }

  // Drop the previous user's data only after the signed-in views have unmounted; removing it
  // while they are still mounted lets a re-render fetch it again.
  const signedOut = session.data === null;
  useEffect(() => {
    if (!signedOut) return;
    void queryClient.cancelQueries({ queryKey: tablesKey });
    queryClient.removeQueries({ queryKey: tablesKey });
  }, [signedOut, queryClient]);

  async function login(username: string, password: string) {
    const user = await signIn(username, password);
    await queryClient.cancelQueries({ queryKey: sessionKey });
    await queryClient.cancelQueries({ queryKey: tablesKey });
    queryClient.removeQueries({ queryKey: tablesKey });
    queryClient.setQueryData(sessionKey, user);
    setNotice(null);
  }

  async function logout() {
    try {
      await signOut();
    } catch (error) {
      if (!(error instanceof BackendError && error.status === 401)) throw error;
    }
    await clearSession();
  }

  if (session.isPending)
    return <p className="p-6 text-sm text-muted-foreground">Checking your session…</p>;
  if (session.isError) {
    return (
      <main className="mx-auto max-w-lg space-y-4 p-6">
        <h1 className="text-xl font-semibold">Can't check your session</h1>
        <p role="alert">{backendErrorMessage(session.error)}</p>
        <Button onClick={() => void session.refetch()}>Try again</Button>
      </main>
    );
  }
  if (!session.data) return <BackendSignIn login={login} notice={notice} />;
  if (session.data.role === "ADMIN")
    return <AdminWorkspace key={session.data.id} user={session.data} logout={logout} />;
  return (
    <TableWorkspace
      key={session.data.id}
      user={session.data}
      logout={logout}
      expire={() => void clearSession("Your session expired. Please sign in again.")}
    />
  );
}

function BackendSignIn({
  login,
  notice,
}: {
  login: (username: string, password: string) => Promise<void>;
  notice: string | null;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      setPassword("");
    } catch (failure) {
      setError(
        failure instanceof BackendError && failure.status === 401
          ? "That username and password don't match an active account."
          : backendErrorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="w-full max-w-sm space-y-6">
        <Logo asLink={false} className="justify-center" />
        {notice && (
          <p role="status" className="text-sm">
            {notice}
          </p>
        )}
        <form onSubmit={submit} className="space-y-4 rounded-2xl border bg-surface p-5">
          <h1 className="font-display text-2xl font-extrabold uppercase">Sign in</h1>
          <p className="text-sm text-muted-foreground">
            Use a provisioned backend account. Registration is not available yet.
          </p>
          <div className="space-y-1.5">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              autoComplete="username"
              autoCapitalize="none"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </div>
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <Button type="submit" variant="hero" size="lg" className="w-full" disabled={busy}>
            <LogIn /> {busy ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </div>
    </main>
  );
}

function TableWorkspace({
  user,
  logout,
  expire,
}: {
  user: BackendUser;
  logout: () => Promise<void>;
  expire: () => void;
}) {
  const queryClient = useQueryClient();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const match = /^\/tables\/([^/]+)\/?$/.exec(pathname);
  const archiveId = /^\/archives\/([^/]+)\/?$/.exec(pathname)?.[1];
  const tableId = match?.[1];
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleLogout() {
    setBusy(true);
    setError(null);
    try {
      await logout();
    } catch (failure) {
      setError(backendErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <RealtimeProvider
      // Updates may have been missed while disconnected, so refetch everything on (re)connect.
      onConnected={() => void queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id] })}
      // A closed socket can mean the session ended; HTTP is authoritative, so ask it. Network
      // failures are ignored here and the socket keeps reconnecting.
      onClosed={() =>
        void currentUser()
          .then((current) => {
            if (current?.id !== user.id) expire();
          })
          .catch(() => {})
      }
    >
      <PersonalUpdates user={user} />
      <AppShell
        user={user}
        pathname={pathname}
        expire={expire}
        onSignOut={() => void handleLogout()}
        signingOut={busy}
      >
        {error && (
          <p role="alert" className="mx-auto max-w-4xl px-4 pt-4 text-loss md:px-8">
            {error}
          </p>
        )}
        {tableId ? (
          // The table page fills the screen, like the felt in a card room.
          <TablePage key={tableId} user={user} tableId={tableId} expire={expire} />
        ) : (
          <div className="mx-auto max-w-4xl space-y-6 px-4 py-8 md:px-8">
            {pathname === "/" ? (
              <>
                <Invitations user={user} expire={expire} />
                <TableList user={user} expire={expire} />
                <CreateTable user={user} expire={expire} />
                <ArchivedTables user={user} />
              </>
            ) : pathname === "/friends" ? (
              <BackendFriends user={user} expire={expire} />
            ) : pathname === "/how-to-play" ? (
              <BackendGuide user={user} expire={expire} />
            ) : pathname === "/preferences" ? (
              <BackendPreferences user={user} expire={expire} />
            ) : archiveId ? (
              <ArchivedTable key={archiveId} user={user} tableId={archiveId} expire={expire} />
            ) : pathname === "/tickets" ? (
              <TicketsPage user={user} expire={expire} />
            ) : (
              <div className="space-y-3">
                <h1 className="text-xl font-semibold">This page is not connected yet</h1>
                <Link to="/" className="text-primary underline">
                  Your tables
                </Link>
              </div>
            )}
          </div>
        )}
      </AppShell>
    </RealtimeProvider>
  );
}

/** Refreshes the table list and displayed profiles when the personal feed says they changed. */
function PersonalUpdates({ user }: { user: BackendUser }) {
  const queryClient = useQueryClient();
  useRealtimeFeed(personalFeed, (event) => {
    if (event.type === "invitations.changed") {
      void queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id, "invites"] });
    } else if (event.type === "tables.changed") {
      void queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id, "list"] });
    } else if (event.type === "profile.changed" && event.resourceId) {
      const id = event.resourceId;
      void queryClient.invalidateQueries({
        predicate: ({ queryKey }) =>
          queryKey[0] === tablesKey[0] && queryKey.at(-2) === "profile" && queryKey.at(-1) === id,
      });
    }
  });
  return null;
}

function TableList({ user, expire }: { user: BackendUser; expire: () => void }) {
  const query = useQuery({
    queryKey: [...tablesKey, user.id, "list"],
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
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

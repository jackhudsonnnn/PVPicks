import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { backendErrorMessage, isUnauthorized } from "@/lib/api-client";
import { currentUser, signIn, signOut, SignIn } from "@/features/auth";
import { ArchivedTable, HomePage, TablePage } from "@/features/tables";
import type { User } from "@/features/people";
import { AdminWorkspace } from "@/features/admin";
import { FriendsPage } from "@/features/social";
import { GuidePage } from "@/features/guide";
import { PreferencesPage } from "@/features/preferences";
import { AppShell } from "./shell";
import { DevPage } from "@/features/devdata";
import { TicketsPage } from "@/features/bets";
import { RealtimeProvider, useRealtimeFeed } from "@/lib/realtime";
import { personalFeed } from "@/lib/realtime-connection";
import { sessionKey, tablesKey } from "@/lib/query-keys";
import { cn } from "@/lib/utils";

export function App() {
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
      if (!isUnauthorized(error)) throw error;
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
  if (!session.data) return <SignIn login={login} notice={notice} />;
  if (session.data.role === "ADMIN")
    return <AdminWorkspace key={session.data.id} user={session.data} logout={logout} />;
  return (
    <TableWorkspace
      key={session.data.id}
      user={session.data}
      logout={logout}
      expire={() => void clearSession("Your session expired. Please sign in again.")}
      endSession={(message) => void clearSession(message)}
    />
  );
}

function TableWorkspace({
  user,
  logout,
  expire,
  endSession,
}: {
  user: User;
  logout: () => Promise<void>;
  expire: () => void;
  /** Signs out with a notice (account deleted). */
  endSession: (message: string) => void;
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
          // Home is wider on desktop for its Tickets column.
          <div
            className={cn(
              "mx-auto space-y-6 px-4 py-8 md:px-8",
              pathname === "/" ? "max-w-6xl" : "max-w-4xl",
            )}
          >
            {pathname === "/" ? (
              <HomePage user={user} expire={expire} />
            ) : pathname === "/friends" ? (
              <FriendsPage user={user} expire={expire} />
            ) : pathname === "/how-to-play" ? (
              <GuidePage user={user} expire={expire} />
            ) : pathname === "/preferences" ? (
              <PreferencesPage
                user={user}
                expire={expire}
                onAccountDeleted={() => endSession("Your account was deleted.")}
              />
            ) : archiveId ? (
              <ArchivedTable key={archiveId} user={user} tableId={archiveId} expire={expire} />
            ) : pathname === "/dev" ? (
              <DevPage user={user} />
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
function PersonalUpdates({ user }: { user: User }) {
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

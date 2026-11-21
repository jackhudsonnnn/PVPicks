import { useEffect, useState } from "react";
import { startPerfReporting } from "@/lib/perf";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { backendErrorMessage, isUnauthorized } from "@/lib/api-client";
import {
  CodeLinkSignIn,
  currentUser,
  signIn,
  signInAs,
  signInWithCode,
  signOut,
  SignIn,
  AdminSignIn,
} from "@/features/auth";
import { ArchivedTable, HomePage, TablePage } from "@/features/tables";
import type { User } from "@/features/people";
import { AdminWorkspace } from "@/features/admin";
import { FriendsPage } from "@/features/social";
import { GuidePage } from "@/features/guide";
import { PreferencesPage } from "@/features/preferences";
import { AppShell } from "./shell";
import { DevPage, resetDemo, useDevAvailable } from "@/features/devdata";
import { DevLinks } from "./dev-links";
import { Logo } from "@/components/logo";
import { TicketsPage } from "@/features/bets";
import { RealtimeProvider, useRealtimeFeed } from "@/lib/realtime";
import { personalFeed } from "@/lib/realtime-connection";
import { sessionKey, tablesKey } from "@/lib/query-keys";
import { cn } from "@/lib/utils";
import { LoadingScreen } from "@/components/loading";
import { toast } from "sonner";

export function App() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [notice, setNotice] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => currentUser(signal),
    retry: false,
  });
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  // Signed out, the dev icons and the Dev page still work on a dev backend.
  const devAvailable = useDevAvailable("guest");

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

  // Browser timings for the admin Perf tab, sent only while someone is signed in.
  const signedInId = session.data?.id ?? null;
  useEffect(() => (signedInId ? startPerfReporting() : undefined), [signedInId]);

  async function login(username: string, password: string) {
    await startSession(await signIn(username, password));
  }

  async function loginAs(userId: string) {
    await startSession(await signInAs(userId));
  }

  async function loginWithCode(username: string, code: string) {
    await startSession(await signInWithCode({ username }, code));
  }

  // Every sign-in starts on Home: the URL may still be the previous session's page (possibly another
  // account's table), and the app routes by URL.
  async function startSession(user: Awaited<ReturnType<typeof signIn>>) {
    await navigate({ to: "/", replace: true });
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

  /**
   * The demo reset ends every session a second in, then seeds for several more. The signed-in views step aside
   * for the whole reset, so nothing reads as "session expired" halfway through.
   */
  async function resetWorld() {
    setResetting(true);
    try {
      await resetDemo();
      await navigate({ to: "/", replace: true });
      await clearSession("The demo world was reset. Pick an account.");
    } catch (error) {
      toast.error(`The reset didn't finish: ${backendErrorMessage(error)}`);
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    } finally {
      setResetting(false);
    }
  }

  if (resetting)
    return <LoadingScreen label="Resetting the demo world" message="Resetting the demo world" />;
  if (session.isPending) return <LoadingScreen label="Checking your session" />;
  if (session.isError) {
    return (
      <main className="mx-auto max-w-lg space-y-4 p-6">
        <h1 className="text-xl font-semibold">Can't check your session</h1>
        <p role="alert">{backendErrorMessage(session.error)}</p>
        <Button onClick={() => void session.refetch()}>Try again</Button>
      </main>
    );
  }
  // A player's QR link works signed in or out: opening it signs in as that player.
  if (pathname === "/signin") return <CodeLinkSignIn onDone={startSession} />;
  if (!session.data) {
    if (pathname === "/dev" && devAvailable)
      return <SignedOutDev onReset={() => void resetWorld()} />;
    if (pathname === "/admin") return <AdminSignIn login={login} notice={notice} />;
    return (
      <SignIn
        loginAs={loginAs}
        loginWithCode={loginWithCode}
        notice={notice}
        extras={devAvailable ? <DevLinks active={false} /> : undefined}
      />
    );
  }
  if (session.data.role === "ADMIN")
    return <AdminWorkspace key={session.data.id} user={session.data} logout={logout} />;
  return (
    <TableWorkspace
      key={session.data.id}
      user={session.data}
      logout={logout}
      expire={() => void clearSession("Your session expired. Please sign in again.")}
      endSession={(message) => void clearSession(message)}
      resetWorld={() => void resetWorld()}
    />
  );
}

/** The Dev page without signing in (dev backend only): the demo reset and the dev sports controls. */
function SignedOutDev({ onReset }: { onReset: () => void }) {
  return (
    <main className="min-h-dvh bg-background px-4 py-6 md:px-8">
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <Logo badge={<DevLinks active />} />
          <Button asChild variant="outline" size="sm">
            <Link to="/">Sign in</Link>
          </Button>
        </div>
        <DevPage scope="guest" onReset={onReset} />
      </div>
    </main>
  );
}

function TableWorkspace({
  user,
  logout,
  expire,
  endSession,
  resetWorld,
}: {
  user: User;
  logout: () => Promise<void>;
  expire: () => void;
  /** Signs out with a notice (account deleted). */
  endSession: (message: string) => void;
  /** Resets the demo world (Dev page); ends the session when done. */
  resetWorld: () => void;
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
          // Home (its Tickets column) and the guide (its card rows) are wider on desktop.
          <div
            className={cn(
              "mx-auto space-y-6 px-4 py-8 md:px-8",
              pathname === "/" || pathname === "/how-to-play" ? "max-w-6xl" : "max-w-4xl",
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
              <DevPage scope={user.id} onReset={resetWorld} />
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

import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { LogIn, LogOut, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Logo } from "@/components/logo";
import { EmptyState } from "@/components/empty-state";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { SegmentedTabs } from "@/components/segmented-tabs";
import {
  backendErrorMessage,
  BackendError,
  currentUser,
  getBet,
  getTable,
  listTableBets,
  listTables,
  signIn,
  signOut,
  type BackendBet,
  type BackendTable,
  type BackendUser,
} from "./backend-api";
import {
  BetMutationStatus,
  PickControls,
  PointsSettlementControl,
  ProposalForm,
  ResultControls,
  type BetActions,
} from "./backend-bet-controls";
import { PersonName } from "./backend-person-name";
import { BackendChat } from "./backend-chat";
import { BackendSettlements } from "./backend-settlements";
import { getChatUnread } from "./chat-api";
import { useBetActions } from "./bet-actions";
import { RealtimeProvider, useRealtimeFeed, useRealtimeStatus } from "./realtime";
import { personalFeed, tableBetsFeed, tableChatFeed, tableViewFeed } from "./realtime-connection";

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
      <div className="min-h-dvh bg-field">
        <header className="glass-bar flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 md:px-8">
          <Logo />
          <div className="flex items-center gap-3">
            <LiveStatus />
            <span className="text-sm">{user.username}</span>
            <Button variant="outline" size="sm" onClick={() => void handleLogout()} disabled={busy}>
              <LogOut /> Sign out
            </Button>
          </div>
        </header>
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8 md:px-8">
          <p className="rounded-lg border bg-surface p-3 text-sm text-muted-foreground">
            Dev backend: U2Pick betting, results, points settlement, receipts and table chat are
            connected and update live. Table management and social features are not connected yet.
          </p>
          {error && (
            <p role="alert" className="text-loss">
              {error}
            </p>
          )}
          {pathname === "/" ? (
            <>
              <TableList user={user} expire={expire} />
              <BackendSettlements user={user} expire={expire} />
            </>
          ) : tableId ? (
            <TableDetails key={tableId} user={user} tableId={tableId} expire={expire} />
          ) : (
            <div className="space-y-3">
              <h1 className="text-xl font-semibold">This page is not connected yet</h1>
              <Link to="/" className="text-primary underline">
                Your tables
              </Link>
            </div>
          )}
        </main>
      </div>
    </RealtimeProvider>
  );
}

/** Refreshes the table list and displayed profiles when the personal feed says they changed. */
function PersonalUpdates({ user }: { user: BackendUser }) {
  const queryClient = useQueryClient();
  useRealtimeFeed(personalFeed, (event) => {
    if (event.type === "tables.changed") {
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

function LiveStatus() {
  const status = useRealtimeStatus();
  if (!status) return null;
  return (
    <span role="status" className="text-xs text-muted-foreground">
      {status === "live" ? "Live" : "Reconnecting…"}
    </span>
  );
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

function TableDetails({
  user,
  tableId,
  expire,
}: {
  user: BackendUser;
  tableId: string;
  expire: () => void;
}) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: [...tablesKey, user.id, tableId],
    queryFn: ({ signal }) => getTable(tableId, signal),
    retry: false,
  });
  useRealtimeFeed(tableViewFeed(tableId), () => {
    // Only the table itself; its bets and chat have their own feeds.
    void queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id, tableId], exact: true });
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  return (
    <section className="space-y-4">
      <Link to="/" className="text-sm text-primary underline">
        Back to tables
      </Link>
      {query.isPending ? (
        <p>Loading table…</p>
      ) : query.isError ? (
        <div className="space-y-3">
          <p role="alert">{backendErrorMessage(query.error)}</p>
          <Button variant="outline" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : (
        <>
          <div className="space-y-4 rounded-lg border bg-surface p-5">
            <div className="flex items-center justify-between gap-3">
              <h1 className="font-display text-3xl font-bold uppercase">
                {query.data.emoji} {query.data.name}
              </h1>
              <Button
                variant="outline"
                size="sm"
                disabled={query.isFetching}
                onClick={() => void query.refetch()}
              >
                <RefreshCw /> Refresh
              </Button>
            </div>
            <p>
              {query.data.memberIds.length}{" "}
              {query.data.memberIds.length === 1 ? "member" : "members"} ·{" "}
              {query.data.hostIds.includes(user.id) ? "You are a host" : "You are a member"}
            </p>
            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">Remainder rule</dt>
                <dd>{query.data.remainderRule}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Who can start bets</dt>
                <dd>{query.data.hostsOnlyBets ? "Hosts only" : "Permitted members"}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">U2Pick</dt>
                <dd>{query.data.u2pickEnabled ? "Enabled" : "Disabled"}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Quick Picks</dt>
                <dd>{query.data.quickPicksEnabled ? "Enabled" : "Disabled"}</dd>
              </div>
            </dl>
          </div>
          <TableActivity user={user} table={query.data} expire={expire} />
        </>
      )}
    </section>
  );
}

/** The table's bets and chat, as tabs; the chat tab shows the unread count. */
function TableActivity({
  user,
  table,
  expire,
}: {
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
}) {
  const [tab, setTab] = useState<"bets" | "chat">("bets");
  const queryClient = useQueryClient();
  const unreadKey = [...tablesKey, user.id, table.id, "chat-unread"];
  const unread = useQuery({
    queryKey: unreadKey,
    queryFn: ({ signal }) => getChatUnread(table.id, signal),
    retry: false,
  });
  useRealtimeFeed(tableChatFeed(table.id), () => {
    void queryClient.invalidateQueries({ queryKey: unreadKey });
  });
  // Shares its cache with the bet list, so bet cards in chat can show the question.
  const bets = useQuery({
    queryKey: [...tablesKey, user.id, table.id, "bets"],
    queryFn: ({ signal }) => listTableBets(table.id, signal),
    retry: false,
  });
  const count = unread.data?.unreadCount ?? 0;
  return (
    <>
      <SegmentedTabs
        ariaLabel="Table activity"
        tabs={[
          { id: "bets", label: "Bets" },
          { id: "chat", label: count > 0 ? `Chat (${count})` : "Chat" },
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === "bets" ? (
        <TableBetting user={user} table={table} expire={expire} />
      ) : (
        <BackendChat
          user={user}
          table={table}
          expire={expire}
          betQuestion={(betId) => bets.data?.find((bet) => bet.id === betId)?.question}
          onRead={() => void queryClient.invalidateQueries({ queryKey: unreadKey })}
        />
      )}
    </>
  );
}

function TableBetting({
  user,
  table,
  expire,
}: {
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
}) {
  const queryClient = useQueryClient();
  const actions = useBetActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id] });
    },
    expire,
  );
  useRealtimeFeed(tableBetsFeed(table.id), () => {
    void queryClient.invalidateQueries({ queryKey: [...tablesKey, user.id, table.id, "bets"] });
  });
  return (
    <>
      <BetMutationStatus actions={actions} />
      <ProposalForm table={table} user={user} actions={actions} />
      <TableBets user={user} table={table} expire={expire} actions={actions} />
    </>
  );
}

function TableBets({
  user,
  table,
  expire,
  actions,
}: {
  user: BackendUser;
  table: BackendTable;
  expire: () => void;
  actions: BetActions;
}) {
  const tableId = table.id;
  const [selected, setSelected] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const betsKey = [...tablesKey, user.id, tableId, "bets"] as const;
  const query = useQuery({
    queryKey: betsKey,
    queryFn: ({ signal }) => listTableBets(tableId, signal),
    retry: false,
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: betsKey });
  }

  return (
    <section className="space-y-4" aria-labelledby="table-bets-heading">
      <div className="flex items-center justify-between gap-3">
        <h2 id="table-bets-heading" className="font-display text-2xl font-bold uppercase">
          Bets
        </h2>
        <Button variant="outline" size="sm" disabled={query.isFetching} onClick={refresh}>
          <RefreshCw /> Refresh bets
        </Button>
      </div>
      {query.data && (
        <PointsSettlementControl table={table} user={user} bets={query.data} actions={actions} />
      )}
      {query.isPending ? (
        <p>Loading bets…</p>
      ) : query.isError ? (
        <div className="space-y-3">
          <p role="alert">{backendErrorMessage(query.error)}</p>
          <Button variant="outline" onClick={refresh}>
            Try again
          </Button>
        </div>
      ) : query.data.length === 0 ? (
        <EmptyState boxed>No bets at this table yet.</EmptyState>
      ) : (
        <ul className="space-y-3">
          <AnimatedList key={tableId}>
            {query.data.map((bet) => (
              <AnimatedItem as="li" key={bet.id} className="rounded-lg border bg-surface p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="break-words font-semibold">{bet.question}</h3>
                    <p className="text-sm text-muted-foreground">
                      {betStatus(bet.status)} · {bet.pickCount}{" "}
                      {bet.pickCount === 1 ? "pick" : "picks"} ·{" "}
                      {bet.forFun ? "For fun" : `${formatPoints(bet.wagerCents)} points`}
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    aria-expanded={selected === bet.id}
                    aria-controls={selected === bet.id ? `bet-details-${bet.id}` : undefined}
                    onClick={() => setSelected(selected === bet.id ? null : bet.id)}
                  >
                    {selected === bet.id ? "Hide details" : "View details"}
                  </Button>
                </div>
                {selected === bet.id && (
                  <BetDetails
                    user={user}
                    table={table}
                    betId={bet.id}
                    expire={expire}
                    queryKey={[...betsKey, bet.id]}
                    actions={actions}
                  />
                )}
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ul>
      )}
    </section>
  );
}

function betStatus(status: BackendBet["status"]) {
  return { ACTIVE: "Active", PENDING: "Locked", SETTLED: "Resolved", WASHED: "Washed" }[status];
}

function formatPoints(cents: number) {
  return (cents / 100).toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function BetDetails({
  user,
  table,
  betId,
  expire,
  queryKey,
  actions,
}: {
  user: BackendUser;
  table: BackendTable;
  betId: string;
  expire: () => void;
  queryKey: readonly string[];
  actions: BetActions;
}) {
  const canBet = !table.disabledPermissions[user.id]?.includes("bets");
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => getBet(table.id, betId, signal),
    retry: false,
  });
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);

  return (
    <div id={`bet-details-${betId}`} className="mt-4 space-y-3 break-words border-t pt-4 text-sm">
      {query.isPending ? (
        <p>Loading bet details…</p>
      ) : query.isError ? (
        <div className="space-y-3">
          <p role="alert">{backendErrorMessage(query.error)}</p>
          <Button variant="outline" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : (
        <>
          <BetFacts bet={query.data} user={user} expire={expire} queryKey={queryKey} />
          <PickControls bet={query.data} tableId={table.id} actions={actions} canBet={canBet} />
          <ResultControls bet={query.data} table={table} user={user} actions={actions} />
        </>
      )}
    </div>
  );
}

function BetFacts({
  bet,
  user,
  expire,
  queryKey,
}: {
  bet: BackendBet;
  user: BackendUser;
  expire: () => void;
  queryKey: readonly string[];
}) {
  const participation =
    bet.myParticipation === "picked"
      ? `Your pick: ${bet.myPick}`
      : bet.myParticipation === "no_guess"
        ? "You joined without a guess."
        : "You have not joined.";
  return (
    <>
      <p>{participation}</p>
      <p>Choices: {bet.choices.join(", ")}</p>
      <p>Lock time: {new Date(bet.locksAt).toLocaleString()}</p>
      {bet.status === "ACTIVE" ? (
        <p className="text-muted-foreground">Other players' picks stay private until lock.</p>
      ) : (
        <>
          <div>
            <p className="font-semibold">Picks after lock</p>
            {bet.picks?.length ? (
              <ul>
                {bet.picks.map((pick) => (
                  <li key={pick.userId} className="break-words">
                    <PersonName id={pick.userId} user={user} expire={expire} queryKey={queryKey} />:{" "}
                    {pick.choice}
                  </li>
                ))}
              </ul>
            ) : (
              <p>No picks.</p>
            )}
          </div>
          {bet.noGuessUserIds && bet.noGuessUserIds.length > 0 && (
            <p className="break-words">
              No guess:{" "}
              {bet.noGuessUserIds.map((id, index) => (
                <span key={id}>
                  {index > 0 && ", "}
                  <PersonName id={id} user={user} expire={expire} queryKey={queryKey} />
                </span>
              ))}
            </p>
          )}
        </>
      )}
      {bet.winningChoice !== undefined && <p>Winning choice: {bet.winningChoice}</p>}
      {bet.washReason !== undefined && <p>Wash reason: {bet.washReason}</p>}
      {(bet.status === "SETTLED" || bet.status === "WASHED") && (
        <p>Table points settlement: {bet.pointsSettled ? "Complete" : "Not yet complete"}</p>
      )}
    </>
  );
}

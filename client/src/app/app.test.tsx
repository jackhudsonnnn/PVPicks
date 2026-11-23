import { summaryOf } from "@/test/api-fixtures";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./app";
import { BackendError } from "@/lib/api-client";
import { currentUser, signInWithCode, signOut } from "@/features/auth/api";
import { getProfile } from "@/features/people/api";
import { getTable, listTables, listTableBalances, getMyBalance } from "@/features/tables/api";
import { listMyBets, listTableBets, type Bet } from "@/features/bets/api";
import { listSettlementGroups } from "@/features/settlements/api";
import { getRelationships, listInvites } from "@/features/social/api";
import type { Invalidation } from "@/lib/realtime-connection";
import { getChatUnread, getChatWindow } from "@/features/chat/api";
import { getDemo, getDevSports } from "@/features/devdata/api";
import { getAppMode } from "@/lib/app-mode-api";

// Several components may listen to one destination (the table view feed carries table and tomato changes), so keep
// each component's latest listener.
const feeds = vi.hoisted(() => new Map<string, Map<unknown, (event: Invalidation) => void>>());
vi.mock("@/lib/realtime", async () => {
  const { useRef } = await import("react");
  return {
    RealtimeProvider: ({ children }: { children: ReactNode }) => children,
    useRealtimeStatus: () => "live",
    useRealtimeFeed: (destination: string | null, listener: (event: Invalidation) => void) => {
      const owner = useRef({});
      if (!destination) return;
      if (!feeds.has(destination)) feeds.set(destination, new Map());
      feeds.get(destination)!.set(owner.current, listener);
    },
  };
});

function deliver(destination: string, event: Partial<Invalidation>) {
  const listeners = feeds.get(destination);
  if (!listeners?.size) throw new Error(`Nothing listens to ${destination}`);
  for (const listener of listeners.values())
    act(() =>
      listener({
        eventId: "123e4567-e89b-42d3-a456-426614174099",
        schemaVersion: 1,
        occurredAt: "2026-11-13T12:00:00Z",
        type: "tables.changed",
        ...event,
      }),
    );
}

const route = vi.hoisted(() => ({ pathname: "/" }));
const navigate = vi.hoisted(() => vi.fn(() => Promise.resolve()));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, to, ...props }: { children: ReactNode; to: string }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
  useNavigate: () => navigate,
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: route }),
}));
// The LAN's code sign-in unless a test says otherwise; the dev picker has its own tests.
vi.mock("@/lib/app-mode-api", () => ({ getAppMode: vi.fn(async () => "lan") }));
vi.mock("@/features/auth/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/auth/api")>()),
  currentUser: vi.fn(),
  signInWithCode: vi.fn(),
  signOut: vi.fn(),
}));
vi.mock("@/features/tables/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/tables/api")>()),
  listTables: vi.fn(),
  getTable: vi.fn(),
  listTableBalances: vi.fn(),
  getMyBalance: vi.fn(),
}));
vi.mock("@/features/bets/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/bets/api")>()),
  listTableBets: vi.fn(),
  listMyBets: vi.fn(),
}));
vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("@/features/chat/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/chat/api")>()),
  getChatUnread: vi.fn(),
  getChatWindow: vi.fn(),
  markRead: vi.fn(),
  listLatestMessages: vi.fn(async () => []),
}));
vi.mock("@/features/social/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/social/api")>()),
  getRelationships: vi.fn(),
  listInvites: vi.fn(),
  searchUsers: vi.fn(),
  setFollow: vi.fn(),
  setBlock: vi.fn(),
  respondToInvite: vi.fn(),
}));
vi.mock("@/features/devdata/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/devdata/api")>()),
  getDemo: vi.fn(),
  getDevSports: vi.fn(),
}));
vi.mock("@/features/settlements/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/settlements/api")>()),
  listSettlementGroups: vi.fn(),
  receiptHistory: vi.fn(),
  acknowledgeReview: vi.fn(),
  transitionLine: vi.fn(),
  transitionGroup: vi.fn(),
}));

const user = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  username: "testuser",
  hue: 2,
  role: "PLAYER" as const,
};
const table = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  name: "Test table",
  emoji: "🏟️",
  memberIds: [user.id],
  hostIds: [user.id],
  hostsOnlyBets: false,
  u2pickEnabled: true,
  pollsEnabled: true,
  jackblackAccess: "everyone" as const,
  holdemAccess: "everyone" as const,
  quickPicksEnabled: false,
  quickPickSharing: false,
  disabledPermissions: {},
};
const bet: Bet = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  tableId: table.id,
  proposerId: user.id,
  modeId: "u2pick",
  config: {},
  question: "Who wins?",
  choices: ["Yes", "No"],
  wagerCents: 125,
  forFun: false,
  status: "ACTIVE",
  locksAt: "2026-11-11T12:00:00Z",
  createdAt: "2026-11-11T11:58:00Z",
  pointsSettled: false,
  pickCount: 2,
  myPick: "Yes",
  myParticipation: "picked",
  viewerCanCall: false,
  viewerCanOverride: false,
};

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  feeds.clear();
  vi.resetAllMocks();
  route.pathname = "/";
  vi.mocked(getDemo).mockResolvedValue(null);
  vi.mocked(getAppMode).mockResolvedValue("lan");
  vi.mocked(getDevSports).mockResolvedValue(null);
  vi.mocked(listTableBets).mockResolvedValue([]);
  vi.mocked(listMyBets).mockResolvedValue({ items: [], nextCursor: null });
  vi.mocked(getRelationships).mockResolvedValue({
    followingIds: [],
    followerIds: [],
    friendIds: [],
    blockedIds: [],
  });
  vi.mocked(listInvites).mockResolvedValue([]);
  vi.mocked(listSettlementGroups).mockResolvedValue([]);
  vi.mocked(listTables).mockResolvedValue([]);
  vi.mocked(listTableBalances).mockResolvedValue([]);
  vi.mocked(getMyBalance).mockResolvedValue(0n);
  vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
  vi.mocked(getChatWindow).mockResolvedValue({ items: [], previousCursor: null, nextCursor: null });
});

/** Opens a bet from the felt's deck and returns the ticket dialog. */
async function openTicket(question: string) {
  fireEvent.click(await screen.findByRole("button", { name: `Open ticket: ${question}` }));
  return within(await screen.findByRole("dialog"));
}

/** The sidebar and the phone top bar each have a Sign out button; only one shows at a time. */
const signOutButton = () => screen.getAllByRole("button", { name: /sign out/i })[0]!;

describe("backend app", () => {
  it("signs in, reads tables and invitations, and revokes the session", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    vi.mocked(signInWithCode).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
    vi.mocked(signOut).mockResolvedValue();
    vi.mocked(listInvites).mockResolvedValue([
      {
        id: "123e4567-e89b-42d3-a456-426614174090",
        tableId: "123e4567-e89b-42d3-a456-426614174091",
        tableName: "Invited table",
        tableEmoji: "🎲",
        fromId: "123e4567-e89b-42d3-a456-426614174092",
        userId: user.id,
        createdAt: "2026-11-13T12:00:00Z",
        memberPreview: [],
        memberCount: 1,
      },
    ]);
    renderApp();
    fireEvent.change(await screen.findByLabelText("Your code"), { target: { value: "K7QM" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: user.username } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));
    // A narrow screen (tests don't match the sidebar's media query): tables and requests are on Home only.
    expect(await screen.findAllByText("Test table")).toHaveLength(1);
    expect(signInWithCode).toHaveBeenCalledWith({ username: user.username }, "K7QM");
    expect(
      await screen.findByRole("button", { name: "Request to join Invited table" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New table" })).toBeInTheDocument();
    fireEvent.click(signOutButton());
    expect(await screen.findByLabelText("Your code")).toBeInTheDocument();
    expect(signOut).toHaveBeenCalledOnce();
  });

  it("greys out Tickets until there's a bet at one of your tables", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
    renderApp();
    await waitFor(() =>
      expect(listMyBets).toHaveBeenCalledWith({ limit: 1 }, expect.any(AbortSignal)),
    );
    await waitFor(() =>
      expect(screen.queryByRole("link", { name: /^Tickets$/i })).not.toBeInTheDocument(),
    );
    expect(screen.getAllByText("Tickets")[0]!.closest("[aria-disabled]")).not.toBeNull();
  });

  it("sends a newcomer with no friends, tables or tickets to find friends instead of New table", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    renderApp();
    await waitFor(() =>
      expect(listMyBets).toHaveBeenCalledWith({ limit: 1 }, expect.any(AbortSignal)),
    );
    fireEvent.click(await screen.findByRole("button", { name: "New table" }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.getByRole("heading", { name: "Add friends first" })).toBeInTheDocument();
    expect(dialog.getByRole("link", { name: "Find friends" })).toHaveAttribute("href", "/friends");
    expect(dialog.queryByLabelText("Table name")).not.toBeInTheDocument();
  });

  it("opens New table as usual once you have a friend", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getRelationships).mockResolvedValue({
      followingIds: ["123e4567-e89b-42d3-a456-426614174004"],
      followerIds: ["123e4567-e89b-42d3-a456-426614174004"],
      friendIds: ["123e4567-e89b-42d3-a456-426614174004"],
      blockedIds: [],
    });
    renderApp();
    await waitFor(() => expect(getRelationships).toHaveBeenCalled());
    fireEvent.click(await screen.findByRole("button", { name: "New table" }));
    expect(await screen.findByLabelText("Table name")).toBeInTheDocument();
  });

  it("links Tickets once there's a bet", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
    vi.mocked(listMyBets).mockResolvedValue({ items: [bet], nextCursor: null });
    renderApp();
    expect(await screen.findAllByRole("link", { name: /^Tickets$/i })).not.toHaveLength(0);
  });

  it("starts every sign-in on Home, not the page the last session left open", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    vi.mocked(signInWithCode).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
    route.pathname = "/tables/123e4567-e89b-42d3-a456-426614174099";
    renderApp();
    fireEvent.change(await screen.findByLabelText("Your code"), { target: { value: "K7QM" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: user.username } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: "/", replace: true }));
  });

  it("offers the Dev page and the docs on a dev backend, even signed out", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    vi.mocked(getDemo).mockResolvedValue({
      users: 204,
      follows: 0,
      blocks: 0,
      tables: 18,
      bots: null,
    });
    renderApp();
    expect(await screen.findByRole("link", { name: "Docs (opens in a new tab)" })).toHaveAttribute(
      "href",
      "/docs/",
    );
    expect(screen.getByRole("link", { name: "Dev data" })).toBeInTheDocument();
  });

  it("opens the Dev page without signing in", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    vi.mocked(getDemo).mockResolvedValue({
      users: 204,
      follows: 0,
      blocks: 0,
      tables: 18,
      bots: null,
    });
    route.pathname = "/dev";
    renderApp();
    expect(await screen.findByText(/204 people, 18 tables/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sign in" })).toBeInTheDocument();
  });

  it("keeps the admin's password form at /admin, off the players' sign-in", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    renderApp();
    expect(await screen.findByLabelText("Your code")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Admin" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  it("shows the admin sign-in at /admin", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    route.pathname = "/admin";
    renderApp();
    expect(await screen.findByRole("heading", { name: "Admin sign-in" })).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
  });

  it("shows the sign-in form without dev icons on a backend without dev data", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    route.pathname = "/dev";
    renderApp();
    expect(await screen.findByLabelText("Your code")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Dev data" })).not.toBeInTheDocument();
  });

  it("loads the requested table independently and reports inaccessible tables", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    const { rerender } = renderApp();
    expect(await screen.findByText(/Test table/)).toBeInTheDocument();
    expect(getTable).toHaveBeenCalledWith(table.id, expect.any(AbortSignal));
    route.pathname = "/tables/123e4567-e89b-42d3-a456-426614174002";
    vi.mocked(getTable).mockRejectedValue(new BackendError("not found", 404));
    rerender(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <App />
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  });

  it("surfaces table failures and returns to sign-in when a session expires", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockRejectedValue(new BackendError("forbidden", 403));
    const first = renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("cannot access");
    first.unmount();
    vi.mocked(listTables).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    await waitFor(() => expect(screen.getByText(/session expired/i)).toBeInTheDocument());
  });

  it("shows bets as tickets without exposing other active picks", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([bet]);
    renderApp();
    const ticket = await openTicket("Who wins?");
    expect(ticket.getByRole("button", { name: "Yes", pressed: true })).toBeInTheDocument();
    expect(ticket.getByRole("link", { name: table.name })).toBeInTheDocument();
    expect(ticket.getByText("1.25")).toBeInTheDocument();
    expect(ticket.queryByText(/\d+ · \d+%/)).not.toBeInTheDocument();
    fireEvent.click(ticket.getByRole("button", { name: "Details" }));
    expect(
      await screen.findByText(/Your pick: Yes\. Other picks stay private/),
    ).toBeInTheDocument();
    expect(getProfile).not.toHaveBeenCalled();
  });

  it("shows locked picks and distinguishes resolved outcomes from points settlement", async () => {
    const locked: Bet = {
      ...bet,
      status: "SETTLED",
      picks: [
        { userId: user.id, choice: "Yes" },
        { userId: "123e4567-e89b-42d3-a456-426614174004", choice: "No" },
      ],
      noGuessUserIds: ["123e4567-e89b-42d3-a456-426614174003"],
      winningChoice: "Yes",
      deltasCents: { [user.id]: 125, "123e4567-e89b-42d3-a456-426614174004": -125 },
    };
    // Closed bets live on the Tickets page.
    route.pathname = "/tickets";
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
    vi.mocked(listMyBets).mockImplementation(async ({ status }) => ({
      items: status === "SETTLED" ? [locked] : [],
      nextCursor: null,
    }));
    vi.mocked(getProfile).mockImplementation(async (id) =>
      id.endsWith("003")
        ? { id, displayName: "Deleted user", deleted: true }
        : { id, username: "otherplayer", hue: 3 },
    );
    renderApp();
    // Tickets opens on Active.
    fireEvent.click(await screen.findByRole("tab", { name: "Resolved" }));
    expect(await screen.findByText("You won")).toBeInTheDocument();
    expect(screen.getByText("+1.25 pts")).toBeInTheDocument();
    expect(screen.getAllByText("1 · 50%")).toHaveLength(2);
    expect(listMyBets).toHaveBeenCalledWith({ status: "SETTLED" }, expect.any(AbortSignal));
    fireEvent.click(screen.getByRole("button", { name: "Details" }));
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByText("otherplayer")).toBeInTheDocument();
    expect(await within(dialog).findByText("Deleted user")).toBeInTheDocument();
    expect(within(dialog).getByText("No guess")).toBeInTheDocument();
    expect(within(dialog).getByText("-1.25")).toBeInTheDocument();
    expect(within(dialog).getByText("Points settled")).toBeInTheDocument();
    expect(within(dialog).getAllByText("Not yet")).toHaveLength(1);
    expect(getProfile).toHaveBeenCalledTimes(2);
  });

  it("shows a profile lookup error and retries without hiding the bet", async () => {
    const memberId = "123e4567-e89b-42d3-a456-426614174003";
    const locked: Bet = {
      ...bet,
      status: "PENDING",
      proposerId: memberId,
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    let available = false;
    vi.mocked(getProfile).mockImplementation(async () => {
      if (!available) throw new BackendError("not found", 404);
      return { id: memberId, username: "player", hue: 1 };
    });
    renderApp();
    const ticket = await openTicket("Who wins?");
    fireEvent.click(ticket.getByRole("button", { name: "Details" }));
    // Details names the person twice (proposer and pick), and each name shows the lookup error.
    const alerts = await screen.findAllByRole("alert");
    for (const alert of alerts) {
      expect(alert).toHaveTextContent(`Profile ${memberId}:`);
      expect(alert).toHaveTextContent("unavailable");
    }
    expect(screen.getByText("Who wins?", { selector: "h3" })).toBeInTheDocument();
    available = true;
    fireEvent.click(screen.getAllByRole("button", { name: "Retry profile" })[0]!);
    const details = screen.getAllByRole("dialog").at(-1)!;
    expect(await within(details).findAllByText("player")).not.toHaveLength(0);
  });

  it("expires the session when a profile lookup returns 401", async () => {
    const locked: Bet = {
      ...bet,
      status: "PENDING",
      proposerId: "123e4567-e89b-42d3-a456-426614174003",
      picks: [{ userId: "123e4567-e89b-42d3-a456-426614174003", choice: "No" }],
      noGuessUserIds: [],
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getProfile).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    // The ticket's proposer avatar looks the person up.
    await openTicket("Who wins?");
    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
  });

  it("clears cached participant profiles on logout", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const memberId = "123e4567-e89b-42d3-a456-426614174003";
    const locked: Bet = {
      ...bet,
      status: "PENDING",
      proposerId: memberId,
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "player", hue: 1 });
    vi.mocked(signOut).mockResolvedValue();
    render(
      <QueryClientProvider client={client}>
        <App />
      </QueryClientProvider>,
    );
    await openTicket("Who wins?");
    const profileKey = ["backend-tables", user.id, "people", "profile", memberId];
    await waitFor(() =>
      expect(client.getQueryData(profileKey)).toMatchObject({ username: "player" }),
    );
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(signOutButton());
    await screen.findByLabelText("Your code");
    expect(client.getQueryData(profileKey)).toBeUndefined();
    expect(
      client
        .getQueriesData({ queryKey: ["backend-tables"] })
        .every(([, data]) => data === undefined),
    ).toBe(true);
  });

  it("surfaces bet list errors and supports retry", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets)
      .mockRejectedValueOnce(new BackendError("failed", 503))
      .mockResolvedValueOnce([bet]);
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(
      await screen.findByRole("button", { name: "Open ticket: Who wins?" }),
    ).toBeInTheDocument();
  });

  it("returns to sign-in when the bet list says the session expired", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
  });

  it("refreshes the table list, the open table and displayed names from realtime feeds", async () => {
    const memberId = "123e4567-e89b-42d3-a456-426614174003";
    const locked: Bet = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "player", hue: 1 });
    renderApp();
    const ticket = await openTicket("Who wins?");
    fireEvent.click(ticket.getByRole("button", { name: "Details" }));
    const dialog = screen.getAllByRole("dialog").at(-1)!;
    expect(await within(dialog).findByText("player")).toBeInTheDocument();

    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "renamed", hue: 1 });
    deliver("/user/queue/me", { type: "profile.changed", resourceId: memberId });
    expect(await within(dialog).findByText("renamed")).toBeInTheDocument();

    vi.mocked(getTable).mockResolvedValue({ ...table, name: "Renamed table" });
    deliver(`/user/queue/tables/${table.id}`, { type: "table.changed", tableId: table.id });
    // The open dialogs hide the page from the accessibility tree.
    expect(
      await screen.findByRole("heading", { name: /Renamed table/, hidden: true }),
    ).toBeInTheDocument();
    expect(listTableBets).toHaveBeenCalledTimes(1);
  });

  it("shows your balance in the points pill and refetches it when it changes", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getMyBalance).mockResolvedValueOnce(-250n).mockResolvedValue(300n);
    renderApp();
    expect(
      (await screen.findAllByLabelText("Your balance, -2.50 points. View settle-ups")).length,
    ).toBeGreaterThan(0);
    deliver("/user/queue/me", { type: "balance.changed" });
    expect(
      (await screen.findAllByLabelText("Your balance, +3.00 points. View settle-ups")).length,
    ).toBeGreaterThan(0);
  });

  it("reloads the table list when the personal feed says it changed", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables)
      .mockResolvedValueOnce([summaryOf(table, user.id)])
      .mockResolvedValue([]);
    renderApp();
    expect(await screen.findAllByText("Test table")).toHaveLength(1);
    deliver("/user/queue/me", { type: "tables.changed" });
    expect(await screen.findByText(/No tables yet/)).toBeInTheDocument();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import {
  BackendError,
  currentUser,
  getProfile,
  getTable,
  listMyBets,
  listTableBets,
  listTables,
  signIn,
  signOut,
  type BackendBet,
} from "./backend-api";
import { listPendingReviews, listSettlementGroups } from "./settlement-api";
import { getRelationships, listInvites } from "./social-api";
import type { Invalidation } from "./realtime-connection";
import { getChatUnread, getChatWindow } from "./chat-api";
import { listTableBalances } from "./tables-api";

// Several components may listen to one destination (the table view feed carries table and tomato changes), so keep
// each component's latest listener.
const feeds = vi.hoisted(() => new Map<string, Map<unknown, (event: Invalidation) => void>>());
vi.mock("./realtime", async () => {
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
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: route }),
}));
vi.mock("./backend-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./backend-api")>();
  return {
    ...actual,
    currentUser: vi.fn(),
    signIn: vi.fn(),
    signOut: vi.fn(),
    listTables: vi.fn(),
    getTable: vi.fn(),
    listTableBets: vi.fn(),
    listMyBets: vi.fn(),
    getProfile: vi.fn(),
  };
});
vi.mock("./tables-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./tables-api")>()),
  listTableBalances: vi.fn(),
}));
vi.mock("./chat-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-api")>()),
  getChatUnread: vi.fn(),
  getChatWindow: vi.fn(),
  markRead: vi.fn(),
}));

vi.mock("./social-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./social-api")>()),
  getRelationships: vi.fn(),
  listInvites: vi.fn(),
  searchUsers: vi.fn(),
  setFollow: vi.fn(),
  setBlock: vi.fn(),
  respondToInvite: vi.fn(),
}));
vi.mock("./settlement-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./settlement-api")>()),
  listPendingReviews: vi.fn(),
  listSettlementCounterparties: vi.fn(),
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
  remainderRule: "host" as const,
  hostsOnlyBets: false,
  u2pickEnabled: true,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
  disabledPermissions: {},
};
const bet: BackendBet = {
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
};

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <BackendApp />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  feeds.clear();
  vi.resetAllMocks();
  route.pathname = "/";
  vi.mocked(listTableBets).mockResolvedValue([]);
  vi.mocked(listPendingReviews).mockResolvedValue([]);
  vi.mocked(getRelationships).mockResolvedValue({
    followingIds: [],
    followerIds: [],
    blockedIds: [],
  });
  vi.mocked(listInvites).mockResolvedValue([]);
  vi.mocked(listSettlementGroups).mockResolvedValue([]);
  vi.mocked(listTables).mockResolvedValue([]);
  vi.mocked(listTableBalances).mockResolvedValue([]);
  vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
  vi.mocked(getChatWindow).mockResolvedValue({ items: [], previousCursor: null, nextCursor: null });
});

/** Opens a bet from the felt's deck and returns the ticket dialog. */
async function openTicket(question: string) {
  fireEvent.click(await screen.findByRole("button", { name: `Open ticket: ${question}` }));
  return within(await screen.findByRole("dialog"));
}

const counts = { ACTIVE: 0, PENDING: 0, SETTLED: 0, WASHED: 0 };

/** The sidebar and the phone top bar each have a Sign out button; only one shows at a time. */
const signOutButton = () => screen.getAllByRole("button", { name: /sign out/i })[0]!;

describe("backend app", () => {
  it("signs in, reads tables and invitations, and revokes the session", async () => {
    vi.mocked(currentUser).mockResolvedValue(null);
    vi.mocked(signIn).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([table]);
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
      },
    ]);
    renderApp();
    fireEvent.change(await screen.findByLabelText("Username"), { target: { value: "testuser" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));
    // Listed on Home and in the sidebar.
    expect(await screen.findAllByText("Test table")).toHaveLength(2);
    expect(signIn).toHaveBeenCalledWith("testuser", "password");
    expect(await screen.findByRole("heading", { name: "Invitations" })).toBeInTheDocument();
    expect(screen.getByText("Create a table")).toBeInTheDocument();
    fireEvent.click(signOutButton());
    expect(await screen.findByLabelText("Username")).toBeInTheDocument();
    expect(signOut).toHaveBeenCalledOnce();
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
        <BackendApp />
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("unavailable");
  });

  it("surfaces table failures and returns to sign-in when a session expires", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables)
      .mockRejectedValueOnce(new BackendError("forbidden", 403))
      .mockRejectedValueOnce(new BackendError("expired", 401));
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("cannot access");
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
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
    expect(ticket.getByText("Picks stay private until lock")).toBeInTheDocument();
    expect(ticket.getByText("1.25")).toBeInTheDocument();
    expect(ticket.queryByText(/\d+ · \d+%/)).not.toBeInTheDocument();
    fireEvent.click(ticket.getByRole("button", { name: "Details" }));
    expect(
      await screen.findByText(/Your pick: Yes\. Other picks stay private/),
    ).toBeInTheDocument();
    expect(getProfile).not.toHaveBeenCalled();
  });

  it("shows locked picks and distinguishes resolved outcomes from points settlement", async () => {
    const locked: BackendBet = {
      ...bet,
      status: "SETTLED",
      picks: [
        { userId: user.id, choice: "Yes" },
        { userId: "123e4567-e89b-42d3-a456-426614174004", choice: "No" },
      ],
      noGuessUserIds: ["123e4567-e89b-42d3-a456-426614174003"],
      remainderRule: "host",
      remainderHostId: user.id,
      winningChoice: "Yes",
      deltasCents: { [user.id]: 125, "123e4567-e89b-42d3-a456-426614174004": -125 },
    };
    // Closed bets live on the Tickets page.
    route.pathname = "/tickets";
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValue([table]);
    vi.mocked(listMyBets).mockImplementation(async ({ status }) => ({
      items: status === "SETTLED" ? [locked] : [],
      nextCursor: null,
      counts: { ...counts, SETTLED: 1 },
    }));
    vi.mocked(getProfile).mockImplementation(async (id) =>
      id.endsWith("003")
        ? { id, displayName: "Deleted user", deleted: true }
        : { id, username: "otherplayer", hue: 3 },
    );
    renderApp();
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
    const locked: BackendBet = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
      remainderRule: "host",
      remainderHostId: user.id,
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
    expect(await screen.findByRole("alert")).toHaveTextContent(`Profile ${memberId}:`);
    expect(screen.getByRole("alert")).toHaveTextContent("unavailable");
    expect(screen.getByText("Who wins?", { selector: "h3" })).toBeInTheDocument();
    available = true;
    fireEvent.click(screen.getByRole("button", { name: "Retry profile" }));
    const details = screen.getAllByRole("dialog").at(-1)!;
    expect(await within(details).findByText("player")).toBeInTheDocument();
  });

  it("expires the session when a profile lookup returns 401", async () => {
    const locked: BackendBet = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: "123e4567-e89b-42d3-a456-426614174003", choice: "No" }],
      noGuessUserIds: [],
      remainderRule: "host",
      remainderHostId: user.id,
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getProfile).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    // The ticket's avatars look the person up.
    await openTicket("Who wins?");
    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
  });

  it("clears cached participant profiles on logout", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const memberId = "123e4567-e89b-42d3-a456-426614174003";
    const locked: BackendBet = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
      remainderRule: "host",
      remainderHostId: user.id,
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "player", hue: 1 });
    vi.mocked(signOut).mockResolvedValue();
    render(
      <QueryClientProvider client={client}>
        <BackendApp />
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
    await screen.findByLabelText("Username");
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
    const locked: BackendBet = {
      ...bet,
      status: "PENDING",
      picks: [{ userId: memberId, choice: "No" }],
      noGuessUserIds: [],
      remainderRule: "host",
      remainderHostId: user.id,
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

  it("reloads the table list when the personal feed says it changed", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValueOnce([table]).mockResolvedValue([]);
    renderApp();
    expect(await screen.findAllByText("Test table")).toHaveLength(2);
    deliver("/user/queue/me", { type: "tables.changed" });
    expect(await screen.findByText("No tables yet.")).toBeInTheDocument();
  });
});

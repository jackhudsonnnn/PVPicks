import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import {
  BackendError,
  currentUser,
  getBet,
  getProfile,
  getTable,
  listTableBets,
  listTables,
  signIn,
  signOut,
  type BackendBet,
} from "./backend-api";
import { listPendingReviews, listSettlementGroups } from "./settlement-api";
import { getRelationships, listInvites } from "./social-api";
import type { Invalidation } from "./realtime-connection";

const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("./realtime", () => ({
  RealtimeProvider: ({ children }: { children: ReactNode }) => children,
  useRealtimeStatus: () => "live",
  useRealtimeFeed: (destination: string, listener: (event: Invalidation) => void) => {
    feeds.set(destination, listener);
  },
}));

function deliver(destination: string, event: Partial<Invalidation>) {
  const listener = feeds.get(destination);
  if (!listener) throw new Error(`Nothing listens to ${destination}`);
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
    getBet: vi.fn(),
    getProfile: vi.fn(),
  };
});

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

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "testuser", hue: 2 };
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
});

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
    expect(await screen.findByText("Test table")).toBeInTheDocument();
    expect(signIn).toHaveBeenCalledWith("testuser", "password");
    expect(await screen.findByRole("heading", { name: "Invitations" })).toBeInTheDocument();
    expect(screen.getByText("Create a table")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /sign out/i }));
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
    expect(listTables).not.toHaveBeenCalled();
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

  it("loads bet list and details without exposing other active picks", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([bet]);
    vi.mocked(getBet).mockResolvedValue(bet);
    renderApp();
    expect(await screen.findByText("Who wins?")).toBeInTheDocument();
    expect(screen.getByText(/2 picks · 1.25 points/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "View details" }));
    expect(await screen.findByText("Your pick: Yes")).toBeInTheDocument();
    expect(screen.getByText(/picks stay private until lock/i)).toBeInTheDocument();
    expect(screen.queryByText("Picks after lock")).not.toBeInTheDocument();
    expect(getProfile).not.toHaveBeenCalled();
    expect(getBet).toHaveBeenCalledWith(table.id, bet.id, expect.any(AbortSignal));
    fireEvent.click(screen.getByRole("button", { name: "Hide details" }));
    expect(screen.queryByText("Your pick: Yes")).not.toBeInTheDocument();
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
    };
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getBet).mockResolvedValue(locked);
    vi.mocked(getProfile).mockImplementation(async (id) =>
      id.endsWith("003")
        ? { id, displayName: "Deleted user", deleted: true }
        : { id, username: "otherplayer", hue: 3 },
    );
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByText("You: Yes")).toBeInTheDocument();
    expect(await screen.findByText("otherplayer: No")).toBeInTheDocument();
    expect(await screen.findByText("Deleted user")).toBeInTheDocument();
    expect(getProfile).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Winning choice: Yes")).toBeInTheDocument();
    expect(screen.getByText("Table points settlement: Not yet complete")).toBeInTheDocument();
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
    vi.mocked(getBet).mockResolvedValue(locked);
    vi.mocked(getProfile)
      .mockRejectedValueOnce(new BackendError("not found", 404))
      .mockResolvedValueOnce({ id: memberId, username: "player", hue: 1 });
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(`Profile ${memberId}:`);
    expect(screen.getByRole("alert")).toHaveTextContent("unavailable");
    fireEvent.click(screen.getByRole("button", { name: "Retry profile" }));
    expect(await screen.findByText("player: No")).toBeInTheDocument();
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
    vi.mocked(getBet).mockResolvedValue(locked);
    vi.mocked(getProfile).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
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
    vi.mocked(getBet).mockResolvedValue(locked);
    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "player", hue: 1 });
    vi.mocked(signOut).mockResolvedValue();
    render(
      <QueryClientProvider client={client}>
        <BackendApp />
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    await screen.findByText("player: No");
    const profileKey = ["backend-tables", user.id, table.id, "bets", bet.id, "profile", memberId];
    expect(client.getQueryData(profileKey)).toMatchObject({ username: "player" });
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByLabelText("Username");
    expect(client.getQueryData(profileKey)).toBeUndefined();
  });

  it("surfaces bet read errors, supports retry, and expires the session on detail 401", async () => {
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets)
      .mockRejectedValueOnce(new BackendError("failed", 503))
      .mockResolvedValueOnce([bet]);
    vi.mocked(getBet).mockRejectedValue(new BackendError("expired", 401));
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent("503");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByText(/session expired/i)).toBeInTheDocument();
  });

  it("refreshes an open bet and clears its cached data on logout", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    route.pathname = `/tables/${table.id}`;
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(getTable).mockResolvedValue(table);
    vi.mocked(listTableBets).mockResolvedValue([bet]);
    vi.mocked(getBet).mockResolvedValue(bet);
    vi.mocked(signOut).mockResolvedValue();
    render(
      <QueryClientProvider client={client}>
        <BackendApp />
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    await screen.findByText("Your pick: Yes");
    fireEvent.click(screen.getByRole("button", { name: "Refresh bets" }));
    await waitFor(() => expect(getBet).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByLabelText("Username");
    expect(
      client
        .getQueriesData({ queryKey: ["backend-tables"] })
        .every(([, data]) => data === undefined),
    ).toBe(true);
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
    vi.mocked(getBet).mockResolvedValue(locked);
    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "player", hue: 1 });
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    await screen.findByText("player: No");

    vi.mocked(getProfile).mockResolvedValue({ id: memberId, username: "renamed", hue: 1 });
    deliver("/user/queue/me", { type: "profile.changed", resourceId: memberId });
    expect(await screen.findByText("renamed: No")).toBeInTheDocument();

    vi.mocked(getTable).mockResolvedValue({ ...table, name: "Renamed table" });
    deliver(`/user/queue/tables/${table.id}`, { type: "table.changed", tableId: table.id });
    expect(await screen.findByText(/Renamed table/)).toBeInTheDocument();
    expect(listTableBets).toHaveBeenCalledTimes(1);
  });

  it("reloads the table list when the personal feed says it changed", async () => {
    vi.mocked(currentUser).mockResolvedValue(user);
    vi.mocked(listTables).mockResolvedValueOnce([table]).mockResolvedValue([]);
    renderApp();
    expect(await screen.findByText("Test table")).toBeInTheDocument();
    deliver("/user/queue/me", { type: "tables.changed" });
    expect(await screen.findByText("No tables yet.")).toBeInTheDocument();
  });
});

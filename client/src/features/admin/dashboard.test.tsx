import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UserAuditView } from "./audit";
import {
  getPerf,
  getPerfLive,
  getAdminHealth,
  getSignInLockouts,
  getSportsGame,
  getUserAudit,
  listFeedback,
  listGameSnapshots,
  listSportsDays,
  listSportsGames,
  listSportsLeagues,
  listAuditLog,
  listUserActivity,
  updateFeedback,
  type PerfReport,
  type SportsGame,
  type UserAudit,
} from "./dashboard-api";
import { FeedbackInbox } from "./feedback";
import { JsonTree } from "./json-tree";
import { PerfTab } from "./perf";
import { SportsTab, type SportsSelection } from "./sports";

vi.mock("./dashboard-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./dashboard-api")>()),
  getUserAudit: vi.fn(),
  listUserActivity: vi.fn(),
  listAuditLog: vi.fn(),
  listFeedback: vi.fn(),
  updateFeedback: vi.fn(),
  getPerf: vi.fn(),
  getPerfLive: vi.fn(),
  getAdminHealth: vi.fn(),
  getSignInLockouts: vi.fn(),
  listSportsLeagues: vi.fn(),
  listSportsDays: vi.fn(),
  listSportsGames: vi.fn(),
  getSportsGame: vi.fn(),
  listGameSnapshots: vi.fn(),
}));

const admin = { id: "123e4567-e89b-42d3-a456-426614174000", username: "admin", hue: 4 };
const playerId = "123e4567-e89b-42d3-a456-426614174001";
const otherId = "123e4567-e89b-42d3-a456-426614174002";
const tableId = "123e4567-e89b-42d3-a456-426614174003";
const at = "2026-11-17T12:00:00Z";

function wrap(node: React.ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      {node}
    </QueryClientProvider>,
  );
}

const audit: UserAudit = {
  profile: {
    id: playerId,
    username: "dana",
    hue: 2,
    status: "ACTIVE",
    createdAt: at,
    deletedAt: null,
    lastSignInAt: at,
    activeSessions: 1,
    codeRotatedAt: at,
  },
  moderation: {
    strikes: 1,
    frozen: false,
    against: { open: 1, resolved: 1, dismissed: 0 },
    filed: { open: 0, resolved: 0, dismissed: 0 },
    recentAgainst: [
      {
        id: "123e4567-e89b-42d3-a456-426614174004",
        reason: "spam",
        note: "",
        status: "OPEN",
        createdAt: at,
        otherId,
        otherName: "eli",
        otherHue: 3,
        tableName: "Den",
        messageSnapshot: "buy now",
      },
    ],
    recentFiled: [],
  },
  people: { following: 3, followers: 2, friends: 2, blocking: 0, blockedBy: 1 },
  tables: [
    {
      id: tableId,
      name: "Den",
      emoji: "🎲",
      role: "HOST",
      joinedAt: at,
      archived: false,
      members: 4,
      messages: 12,
      polls: 1,
      betsProposed: 3,
      picks: 9,
      netCents: -250,
    },
  ],
  archivedTables: 0,
  betting: {
    proposed: 3,
    picks: 9,
    pending: 0,
    decided: { won: 3, lost: 1 },
    washed: 1,
    fourFun: { won: 0, lost: 0 },
    winRate: 0.75,
    netCents: 500,
    biggestWinCents: 300,
    biggestLossCents: -100,
    byMode: [{ mode: "u2pick", won: 3, lost: 1, washed: 1, open: 1 }],
  },
  money: {
    settles: 2,
    paidCents: 250,
    collectedCents: 100,
    sendsRejectedByOthers: 1,
    rejectedTheirSends: 0,
    pendingRejectWindow: 0,
    debtCapCents: null,
  },
  activity: Array.from({ length: 30 }, (_, i) => ({
    date: `2026-10-${String(i + 1).padStart(2, "0")}`,
    messages: i % 3,
    picks: 1,
    bets: 0,
    settles: 0,
  })),
};

beforeEach(() => vi.resetAllMocks());

describe("user audit", () => {
  it("shows the record, money, reports and a logged keyword search", async () => {
    vi.mocked(getUserAudit).mockResolvedValue(audit);
    vi.mocked(listAuditLog).mockResolvedValue({
      items: [
        {
          id: "123e4567-e89b-42d3-a456-426614174009",
          adminId: "123e4567-e89b-42d3-a456-426614174008",
          adminName: "admin",
          adminHue: 4,
          action: "search_activity",
          subjectId: playerId,
          subjectName: "dana",
          subjectHue: 2,
          query: "pizza",
          at,
        },
      ],
      nextCursor: null,
    });
    vi.mocked(listUserActivity).mockImplementation(async (_id, filter) => ({
      items:
        filter.query === "pizza"
          ? [
              {
                kind: "poll",
                id: "123e4567-e89b-42d3-a456-426614174005",
                tableId,
                tableName: "Den",
                text: "Pizza tonight?",
                options: ["Yes", "No"],
                mode: null,
                status: null,
                wagerCents: null,
                createdAt: at,
              },
            ]
          : [],
      nextCursor: null,
    }));
    const onOpenPlayer = vi.fn();
    const onOpenReports = vi.fn();
    wrap(
      <UserAuditView
        user={admin}
        playerId={playerId}
        onBack={() => {}}
        onOpenReports={onOpenReports}
        onOpenPlayer={onOpenPlayer}
      />,
    );
    expect(await screen.findByRole("heading", { name: "dana" })).toBeInTheDocument();
    expect(screen.getByText("Win rate").nextElementSibling).toHaveTextContent("75%");
    expect(screen.getByText("3–1 on 4 decided")).toBeInTheDocument();
    expect(screen.getByText(/This view is logged/)).toBeInTheDocument();
    expect(screen.getByText("“buy now”")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "eli" }));
    expect(onOpenPlayer).toHaveBeenCalledWith(otherId);
    fireEvent.click(screen.getByRole("button", { name: "Open Reports" }));
    expect(onOpenReports).toHaveBeenCalled();
    expect(await screen.findByText("Nothing written yet.")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Search what they wrote"), {
      target: { value: "pizza" },
    });
    const hit = await screen.findByText("Pizza", { selector: "mark" });
    expect(hit.closest("li")).toHaveTextContent("Pizza tonight?");
    expect(listUserActivity).toHaveBeenLastCalledWith(
      playerId,
      { kind: null, query: "pizza" },
      null,
      expect.anything(),
    );
    // Audited by starts collapsed and only loads once opened.
    expect(listAuditLog).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /Audited by/ }));
    expect(await screen.findByText(/searched “pizza”/)).toBeInTheDocument();
    expect(listAuditLog).toHaveBeenCalledWith(null, expect.anything(), playerId);
  });
});

describe("feedback inbox", () => {
  it("lists new feedback and marks it done with a note", async () => {
    vi.mocked(listFeedback).mockResolvedValue({
      items: [
        {
          id: "123e4567-e89b-42d3-a456-426614174006",
          userId: playerId,
          username: "dana",
          hue: 2,
          kind: "bug",
          text: "The ticker froze",
          link: null,
          status: "NEW",
          adminNote: null,
          handledBy: null,
          handledAt: null,
          createdAt: at,
        },
      ],
      nextCursor: null,
      counts: { open: 1, done: 4 },
    });
    vi.mocked(updateFeedback).mockResolvedValue();
    const onOpenPlayer = vi.fn();
    wrap(<FeedbackInbox user={admin} onOpenPlayer={onOpenPlayer} />);
    expect(await screen.findByText("The ticker froze")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Status" })).toHaveTextContent("New · 1");
    fireEvent.click(screen.getByRole("button", { name: "dana" }));
    expect(onOpenPlayer).toHaveBeenCalledWith(playerId);
    fireEvent.change(screen.getByLabelText("Private note"), { target: { value: "fixed in 1.2" } });
    fireEvent.click(screen.getByRole("button", { name: "Mark done" }));
    await waitFor(() =>
      expect(updateFeedback).toHaveBeenCalledWith(
        "123e4567-e89b-42d3-a456-426614174006",
        "DONE",
        "fixed in 1.2",
      ),
    );
  });
});

const stats = (requests: number, p95: number | null, serverErrors = 0) => ({
  requests,
  serverErrors,
  clientErrors: 0,
  rateLimited: 0,
  avgMs: p95,
  p50: p95,
  p95,
  p99: p95,
  maxMs: p95 ?? 0,
});

const report: PerfReport = {
  window: "1h",
  source: "server",
  from: "2026-11-17T11:00:00Z",
  to: at,
  stepSeconds: 60,
  method: null,
  route: null,
  totals: stats(300, 40, 5),
  series: [{ at, stats: stats(300, 40, 5) }],
  routes: [
    { method: "GET", route: "/tables/{tableId}", stats: stats(200, 30) },
    { method: "POST", route: "/bets/{betId}/pick", stats: stats(100, 1500, 5) },
  ],
};

describe("perf", () => {
  it("shows live gauges, flags slow and failing routes, and drills into one", async () => {
    vi.mocked(getPerf).mockResolvedValue(report);
    vi.mocked(getSignInLockouts).mockResolvedValue({
      playerLimit: 10,
      addressLimit: 100,
      address: { wrongCodes: 3, locked: false, resetsAt: null },
      players: [],
    });
    vi.mocked(getAdminHealth).mockResolvedValue({
      checkedAt: at,
      postgres: "UP",
      redis: "UP",
      kafka: "NOT_CHECKED",
    });
    vi.mocked(getPerfLive).mockResolvedValue({
      checkedAt: at,
      startedAt: "2026-11-17T10:00:00Z",
      uptimeSeconds: 7200,
      host: "box",
      version: null,
      database: { active: 2, idle: 8, waiting: 3, max: 10 },
      jvm: {
        heapUsedBytes: 512 * 1024 * 1024,
        heapMaxBytes: 1024 * 1024 * 1024,
        threads: 50,
        processCpu: 0.2,
        processors: 8,
        javaVersion: "21",
      },
      outbox: { pending: 0, oldestPendingSeconds: null },
      feeds: [{ league: "NBA", lastSuccessAt: at, lastFailureAt: null, lastFailure: null }],
    });
    const onChange = vi.fn();
    wrap(<PerfTab user={admin} window="1h" source="server" route={null} onChange={onChange} />);
    expect(await screen.findByText("2h 0m")).toBeInTheDocument();
    expect(screen.getByText("2/10")).toBeInTheDocument();
    expect(screen.getByText("512.0 MB")).toBeInTheDocument();
    const table = screen.getByRole("table");
    const slow = within(table).getByText("/bets/{betId}/pick").closest("tr")!;
    expect(slow).toHaveTextContent("failing");
    expect(slow).toHaveTextContent("5.00%");
    fireEvent.click(within(slow).getByRole("button", { name: /\/bets\/\{betId\}\/pick/ }));
    expect(onChange).toHaveBeenCalledWith({
      window: "1h",
      source: "server",
      route: "POST /bets/{betId}/pick",
    });
    fireEvent.click(screen.getByRole("tab", { name: "24h" }));
    expect(onChange).toHaveBeenLastCalledWith({ window: "24h", source: "server", route: null });
  });
});

describe("json tree", () => {
  it("selects a node and shows its path for copying", () => {
    render(<JsonTree label="game" value={{ players: [{ stats: { pts: 31 } }] }} />);
    fireEvent.click(screen.getByText("players"));
    fireEvent.click(screen.getByText("Expand all"));
    fireEvent.click(screen.getByText("pts"));
    expect(screen.getByTitle("game.players[0].stats.pts")).toBeInTheDocument();
  });
});

const game: SportsGame = {
  id: "NBA-1",
  league: "NBA",
  providerId: "1",
  status: "LIVE",
  startsAt: "2026-11-17T00:30:00Z",
  period: 2,
  clock: "5:00",
  away: { abbreviation: "NYK", name: "Knicks", city: "New York", score: 48, periods: [30, 18] },
  home: { abbreviation: "BOS", name: "Celtics", city: "Boston", score: 50, periods: [25, 25] },
  observedAt: at,
  snapshots: 2,
  bets: 1,
};

describe("sports explorer", () => {
  it("walks league, day and game, then inspects the score and timeline", async () => {
    vi.mocked(listSportsLeagues).mockResolvedValue([
      {
        league: "NBA",
        games: 10,
        live: 1,
        firstDay: "2026-11-01",
        lastDay: "2026-11-17",
        lastSuccessAt: at,
        lastFailureAt: null,
        lastFailure: null,
      },
    ]);
    vi.mocked(listSportsDays).mockResolvedValue([
      { date: "2026-11-16", games: 1, live: 1, finals: 0 },
    ]);
    vi.mocked(listSportsGames).mockResolvedValue([game]);
    vi.mocked(getSportsGame).mockResolvedValue({
      game,
      firstObservedAt: at,
      players: [],
      bets: [{ mode: "total_disaster", status: "ACTIVE", count: 1 }],
    });
    vi.mocked(listGameSnapshots).mockResolvedValue([
      {
        observedAt: "2026-11-17T01:00:00Z",
        status: "LIVE",
        providerState: null,
        period: 1,
        clock: "0:00",
        homeScore: 25,
        awayScore: 30,
        homePeriods: [25],
        awayPeriods: [30],
      },
      {
        observedAt: "2026-11-17T01:20:00Z",
        status: "LIVE",
        providerState: null,
        period: 2,
        clock: "5:00",
        homeScore: 50,
        awayScore: 48,
        homePeriods: [25, 25],
        awayPeriods: [30, 18],
      },
    ]);
    let selection: SportsSelection = {
      league: null,
      day: null,
      game: null,
      panel: "score",
      player: null,
    };
    const onSelect = vi.fn((next: SportsSelection) => {
      selection = next;
      view.rerender(
        <QueryClientProvider client={client}>
          <SportsTab user={admin} selection={selection} onSelect={onSelect} />
        </QueryClientProvider>,
      );
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={client}>
        <SportsTab user={admin} selection={selection} onSelect={onSelect} />
      </QueryClientProvider>,
    );
    // NBA until another league is picked from the dropdown.
    expect(await screen.findByRole("combobox", { name: "League" })).toHaveTextContent("NBA");
    fireEvent.click(await screen.findByRole("button", { name: /2026-11-16/ }));
    fireEvent.click(await screen.findByRole("button", { name: /NYK @ BOS/ }));
    expect(selection).toMatchObject({ league: "NBA", day: "2026-11-16", game: "NBA-1" });
    expect(await screen.findByText(/live, every 5 s/)).toBeInTheDocument();
    expect(screen.getByText("Total Disaster · active · 1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Timeline" }));
    expect(await screen.findByText(/Snapshot 2 of 2/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Previous snapshot" }));
    expect(screen.getByText(/Snapshot 1 of 2/)).toBeInTheDocument();
    expect(screen.getByText("Changed: first snapshot")).toBeInTheDocument();
  });
});

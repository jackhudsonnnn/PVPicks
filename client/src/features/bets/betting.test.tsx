import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HOME_TICKETS } from "@/lib/use-media-query";
import { App } from "@/app/app";
import { getChatUnread, getChatWindow, listLatestMessages } from "@/features/chat/api";
import { listInvites } from "@/features/social/api";
import { listTableBalances, getMyBalance, getTable, listTables } from "@/features/tables/api";
import { getGamePlayers, getTicker, type TickerGame } from "@/features/sports/api";
import { currentUser } from "@/features/auth/api";
import { getBetLiveStats, listModes, listMyBets, listTableBets, type Bet } from "./api";
import { modeAvailability, summaryOf } from "@/test/api-fixtures";
import { mutationRequest } from "@/lib/api-client";
import { PICK_WINDOW_DEFAULT } from "./wager";

const route = vi.hoisted(() => ({ pathname: "/", search: {} as Record<string, string> }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({
    select,
  }: {
    select: (state: { location: { pathname: string; search: Record<string, string> } }) => unknown;
  }) => select({ location: route }),
}));
vi.mock("@/features/auth/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/auth/api")>()),
  currentUser: vi.fn(),
}));
vi.mock("@/features/tables/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/tables/api")>()),
  getTable: vi.fn(),
  listTables: vi.fn(),
  listTableBalances: vi.fn(),
  getMyBalance: vi.fn(),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listTableBets: vi.fn(),
  listMyBets: vi.fn(),
  listModes: vi.fn(),
  getBetLiveStats: vi.fn(),
}));
vi.mock("@/lib/api-client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api-client")>()),
  mutationRequest: vi.fn(),
}));
vi.mock("@/features/sports/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/sports/api")>()),
  getTicker: vi.fn(),
  getGamePlayers: vi.fn(),
}));
vi.mock("@/features/chat/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/chat/api")>()),
  getChatUnread: vi.fn(),
  getChatWindow: vi.fn(),
  markRead: vi.fn(),
  listLatestMessages: vi.fn(),
}));
vi.mock("@/features/social/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/social/api")>()),
  listInvites: vi.fn(),
}));

const user = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  username: "host",
  hue: 1,
  role: "PLAYER" as const,
};
const table = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  name: "Test table",
  emoji: "T",
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
  wagerCents: 100,
  forFun: false,
  status: "ACTIVE",
  // Far enough ahead that the ticket stays open for picks during the test.
  locksAt: new Date(Date.now() + 10 * 60_000).toISOString(),
  createdAt: new Date(Date.now() - 60_000).toISOString(),
  pointsSettled: false,
  pickCount: 0,
  myPick: null,
  myParticipation: "not_joined",
  viewerCanCall: false,
  viewerCanOverride: false,
  participantIds: [],
};

const locked: Bet = {
  ...bet,
  status: "PENDING",
  settledBy: "hosts",
  pickCount: 1,
  picks: [{ userId: user.id, choice: "Yes" }],
};

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
}

async function confirm(label: string) {
  fireEvent.click(await screen.findByRole("button", { name: label }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
}

/** Opens the ticket's "Call it" menu (Radix opens menus from the keyboard in jsdom) and confirms a result. */
async function callIt(result: string) {
  const trigger = await screen.findByRole("button", { name: "Call it" });
  fireEvent.keyDown(trigger, { key: "Enter" });
  fireEvent.click(await screen.findByRole("menuitem", { name: result }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
}

/** Opens the proposal sheet on U2Pick and fills the question. */
async function startU2Pick(question: string) {
  fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
  fireEvent.click(await screen.findByRole("button", { name: /U2Pick/ }));
  fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: question } });
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  route.pathname = `/tables/${table.id}`;
  route.search = {};
  vi.mocked(currentUser).mockResolvedValue(user);
  vi.mocked(getTable).mockResolvedValue(table);
  vi.mocked(listTableBets).mockResolvedValue([bet]);
  vi.mocked(listTables).mockResolvedValue([summaryOf(table, user.id)]);
  vi.mocked(listTableBalances).mockResolvedValue([]);
  vi.mocked(getMyBalance).mockResolvedValue(0n);
  vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
  vi.mocked(getChatWindow).mockResolvedValue({ items: [], previousCursor: null, nextCursor: null });
  vi.mocked(getTicker).mockResolvedValue([liveGame]);
  vi.mocked(listLatestMessages).mockResolvedValue([{ tableId: table.id, message: null }]);
  vi.mocked(listInvites).mockResolvedValue([]);
  vi.mocked(listModes).mockResolvedValue(modeAvailability as Map<Bet["modeId"], boolean>);
});

const liveGame: TickerGame = {
  id: "NBA-18447",
  league: "NBA",
  status: "LIVE",
  startsAt: "2026-11-14T00:00:00Z",
  period: 3,
  clock: "Q3 5:12",
  home: { abbreviation: "BOS", score: 80 },
  away: { abbreviation: "NYK", score: 77 },
  observedAt: "2026-11-14T01:30:00Z",
  dev: false,
};

/** Opens the front bet on the felt and returns the ticket dialog. */
async function openTicket() {
  fireEvent.click(await screen.findByRole("button", { name: "Open ticket: Who wins?" }));
  return within(await screen.findByRole("dialog"));
}

/** The side panel and the phone tab strip both have the tab; either opens it. */
async function openTab(name: string) {
  fireEvent.click((await screen.findAllByRole("tab", { name }))[0]!);
}
afterEach(() => vi.unstubAllGlobals());

describe("U2Pick betting", () => {
  it("resends a finished bet: the proposal sheet opens filled in with its terms", async () => {
    const settled: Bet = {
      ...locked,
      status: "SETTLED",
      question: "Will Sam be late?",
      choices: ["Yes", "No", "Maybe"],
      winningChoice: "Yes",
      settledBy: "anyone",
    };
    vi.mocked(listTableBets).mockResolvedValue([settled]);
    route.search = { resend: settled.id };
    renderApp();
    expect(await screen.findByLabelText("Bet question")).toHaveValue("Will Sam be late?");
    expect(screen.getByDisplayValue("Maybe")).toBeInTheDocument();
  });

  it("opens a mode's info from the (i) beside its name, in place of the setup", async () => {
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    fireEvent.click(screen.getByRole("button", { name: /U2Pick/ }));
    // The setup has no long explanations; they're behind the (i).
    expect(screen.queryByText(/Sending a bet doesn't place your pick/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "How U2Pick works" }));
    expect(screen.getByText("How it works")).toBeInTheDocument();
    expect(screen.getByText("Who's late")).toBeInTheDocument();
    expect(screen.getByText(/Sending a bet doesn't place your pick/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Bet question")).not.toBeInTheDocument();
    // Back returns to the setup as it was.
    fireEvent.click(screen.getAllByRole("button", { name: "Back to setup" })[0]!);
    expect(screen.getByLabelText("Bet question")).toBeInTheDocument();
  });

  it("shows play-by-play modes as coming soon and sends a U2Pick from the preview", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(JSON.stringify(bet)));
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    // Only Choose Their Fate needs GOAT play-by-play; every other mode is live.
    expect(screen.getByRole("button", { name: /Choose Their Fate/ })).toBeDisabled();
    expect(screen.getAllByText("Coming soon")).toHaveLength(1);
    expect(screen.getByRole("button", { name: /Either Or/ })).toBeEnabled();
    expect(screen.getByRole("button", { name: /Total Disaster/ })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: /U2Pick/ }));
    expect(screen.getByRole("button", { name: "Preview ticket" })).toBeDisabled();
    expect(screen.getByText("Write what you're betting on.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: " Who wins? " } });
    // The wager slider: Page Up jumps ten 0.10 steps, 1.00 to 2.00.
    fireEvent.keyDown(screen.getByRole("slider", { name: "Wager in points" }), { key: "PageUp" });
    fireEvent.click(screen.getByRole("tab", { name: "5m" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    expect(await screen.findByText("Preview ticket", { selector: "h2" })).toBeInTheDocument();
    expect(mutationRequest).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: /Send · 2.00 pts/ }));
    await waitFor(() => expect(mutationRequest).toHaveBeenCalledOnce());
    expect(mutationRequest).toHaveBeenCalledWith(
      `/tables/${table.id}/bets`,
      "POST",
      expect.any(String),
      {
        modeId: "u2pick",
        config: {},
        question: "Who wins?",
        choices: ["Yes", "No"],
        wagerCents: 200,
        durationSec: 300,
        forFun: false,
        settledBy: "hosts",
      },
    );
    // Success is silent; the action is done once its saved retry is cleared.
    await waitFor(() => expect(localStorage.getItem(`p2picks:bet-action:${user.id}`)).toBeNull());
  });

  it("proposes a Prop Hunt on a game's player, with the line starting above their number", async () => {
    vi.mocked(getGamePlayers).mockResolvedValue({
      stats: [
        { key: "points", label: "Points" },
        { key: "rebounds", label: "Rebounds" },
      ],
      players: [
        {
          id: "NBA-1",
          name: "Ann Archer",
          position: "G",
          team: "BOS",
          played: true,
          stats: { points: 20 },
        },
        {
          id: "NBA-2",
          name: "Ben Baker",
          position: "F",
          team: "NYK",
          played: true,
          stats: { points: 8 },
        },
      ],
    });
    vi.mocked(mutationRequest).mockResolvedValue(
      new Response(JSON.stringify({ ...bet, modeId: "prop_hunt", gameId: liveGame.id })),
    );
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    fireEvent.click(screen.getByRole("button", { name: /Prop Hunt/ }));
    expect(await screen.findByRole("tab", { name: "Points" })).toBeInTheDocument();
    expect(screen.getByText("Pick a player.")).toBeInTheDocument();
    const trigger = screen.getByRole("combobox", { name: "Player" });
    fireEvent.keyDown(trigger, { key: "Enter" });
    fireEvent.click(
      await screen.findByRole("option", { name: /G · Ann Archer · BOS · 20 points/ }),
    );
    expect(screen.getByLabelText("Stat line")).toHaveTextContent("20.5");
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    expect(await screen.findByText("Ann Archer points: over or under 20.5?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Send · 1.00 pts/ }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/tables/${table.id}/bets`,
        "POST",
        expect.any(String),
        expect.objectContaining({
          modeId: "prop_hunt",
          gameId: liveGame.id,
          config: { line: 20.5, stat: "points", players: ["NBA-1"] },
        }),
      ),
    );
  });

  it("switching league starts the player setup over on that league's game", async () => {
    const nflGame: TickerGame = {
      ...liveGame,
      id: "NFL-1",
      league: "NFL",
      clock: "Q2 3:00",
      home: { abbreviation: "PHI", score: 14 },
      away: { abbreviation: "DAL", score: 7 },
    };
    vi.mocked(getTicker).mockResolvedValue([liveGame, nflGame]);
    vi.mocked(getGamePlayers).mockImplementation(async (gameId) =>
      gameId === liveGame.id
        ? {
            stats: [
              { key: "points", label: "Points" },
              { key: "rebounds", label: "Rebounds" },
            ],
            players: [
              {
                id: "NBA-1",
                name: "Ann Archer",
                position: "G",
                team: "BOS",
                played: true,
                stats: {},
              },
            ],
          }
        : {
            stats: [{ key: "passing_yards", label: "Passing yards" }],
            players: [
              {
                id: "NFL-9",
                name: "Cal Carter",
                position: "QB",
                team: "PHI",
                played: true,
                stats: {},
              },
            ],
          },
    );
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    fireEvent.click(screen.getByRole("button", { name: /Prop Hunt/ }));
    fireEvent.click(await screen.findByRole("tab", { name: "Rebounds" }));
    fireEvent.keyDown(screen.getByRole("combobox", { name: "Player" }), { key: "Enter" });
    fireEvent.click(await screen.findByRole("option", { name: /Ann Archer/ }));
    expect(screen.getByRole("combobox", { name: "Player" })).toHaveTextContent("Ann Archer");

    fireEvent.click(screen.getByRole("tab", { name: "NFL" }));
    expect(
      await screen.findByRole("radio", { name: /DAL.*PHI/, checked: true }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("tab", { name: "Passing yards", selected: true }),
    ).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Player" })).toHaveTextContent("Choose a player");
    expect(screen.getByText("Pick a player.")).toBeInTheDocument();
  });

  it("proposes a Total Disaster on a live game; the server builds the question", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(
      new Response(
        JSON.stringify({
          ...bet,
          modeId: "total_disaster",
          gameId: liveGame.id,
          question: "NYK + BOS combined: over or under 225.5?",
          choices: ["Over 225.5", "Under 225.5"],
        }),
      ),
    );
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    fireEvent.click(screen.getByRole("button", { name: /Total Disaster/ }));
    expect(
      await screen.findByRole("radio", { name: /NYK.*BOS/, checked: true }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Total points line")).toHaveTextContent("224.5");
    fireEvent.click(screen.getByRole("button", { name: "Increase Total points line" }));
    expect(screen.getByLabelText("Total points line")).toHaveTextContent("225.5");
    expect(screen.queryByText(/whole-number line/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("switch", { name: "Overtime counts" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    expect(await screen.findByText("NYK + BOS combined: over or under 225.5?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Send · 1.00 pts/ }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/tables/${table.id}/bets`,
        "POST",
        expect.any(String),
        {
          modeId: "total_disaster",
          gameId: liveGame.id,
          config: { line: 225.5 },
          includesOvertime: false,
          wagerCents: 100,
          durationSec: PICK_WINDOW_DEFAULT,
          forFun: false,
        },
      ),
    );
  });

  it("shows a player bet's live stats in Details: when proposed, at lock and now", async () => {
    vi.mocked(getBetLiveStats).mockResolvedValue({
      gameId: liveGame.id,
      league: "NFL",
      status: "LIVE",
      period: 3,
      clock: "Q3 5:12",
      away: { abbreviation: "BUF", score: 17 },
      home: { abbreviation: "NE", score: 21 },
      closed: false,
      rows: [
        {
          label: "Drake Maye passing yards",
          detail: "QB · NE",
          proposed: 112,
          locked: 140,
          current: 233,
          target: 250.5,
          targetLabel: "Line",
        },
      ],
    });
    vi.mocked(listTableBets).mockResolvedValue([
      {
        ...locked,
        modeId: "prop_hunt",
        gameId: liveGame.id,
        question: "Who wins?",
        choices: ["Over 250.5", "Under 250.5"],
        picks: [{ userId: user.id, choice: "Over 250.5" }],
      },
    ]);
    renderApp();
    const ticket = await openTicket();
    fireEvent.click(ticket.getByRole("button", { name: /Details/ }));
    const details = within(await screen.findByRole("dialog", { name: "Who wins?" }));
    expect(await details.findByText("Drake Maye passing yards")).toBeInTheDocument();
    expect(details.getByText("QB · NE")).toBeInTheDocument();
    expect(details.getByText("Line 250.5")).toBeInTheDocument();
    expect(details.getByLabelText("Proposed: 112")).toBeInTheDocument();
    expect(details.getByLabelText("At lock: 140")).toBeInTheDocument();
    expect(details.getByLabelText("Now: 233")).toBeInTheDocument();
    expect(details.getByText(/BUF 17 @ NE 21 · Q3 5:12/)).toBeInTheDocument();
    expect(getBetLiveStats).toHaveBeenCalledWith(locked.id, expect.anything());
  });

  it("shows a called bet's stats as its Result, frozen when it was called", async () => {
    vi.mocked(getBetLiveStats).mockResolvedValue({
      gameId: liveGame.id,
      league: "NFL",
      status: "LIVE",
      period: 4,
      clock: "Q4 2:10",
      away: { abbreviation: "BUF", score: 24 },
      home: { abbreviation: "NE", score: 21 },
      closed: true,
      rows: [
        {
          label: "Drake Maye passing yards",
          detail: "QB · NE",
          proposed: 112,
          locked: 140,
          current: 251,
          target: 250.5,
          targetLabel: "Line",
        },
      ],
    });
    vi.mocked(listTableBets).mockResolvedValue([
      {
        // The header follows the server's closed flag (set once the bet is called).
        ...locked,
        modeId: "prop_hunt",
        gameId: liveGame.id,
        question: "Who wins?",
        choices: ["Over 250.5", "Under 250.5"],
        picks: [{ userId: user.id, choice: "Over 250.5" }],
      },
    ]);
    renderApp();
    const ticket = await openTicket();
    fireEvent.click(ticket.getByRole("button", { name: /Details/ }));
    const details = within(await screen.findByRole("dialog", { name: "Who wins?" }));
    expect(await details.findByRole("heading", { name: /^Result/ })).toBeInTheDocument();
    expect(details.queryByText("Live stats")).not.toBeInTheDocument();
    expect(details.getByLabelText("Result: 251")).toBeInTheDocument();
    expect(details.getByText(/BUF 24 @ NE 21 · at Q4 2:10/)).toBeInTheDocument();
  });

  it("shows a pending sports bet's progress on the ticket itself", async () => {
    vi.mocked(getBetLiveStats).mockResolvedValue({
      gameId: liveGame.id,
      league: "NFL",
      status: "LIVE",
      period: 3,
      clock: "Q3 5:12",
      away: { abbreviation: "BUF", score: 17 },
      home: { abbreviation: "NE", score: 21 },
      closed: false,
      rows: [
        {
          label: "Drake Maye passing yards",
          detail: "QB · NE",
          proposed: 112,
          locked: 140,
          current: 212,
          target: 250.5,
          targetLabel: "Line",
        },
      ],
    });
    vi.mocked(listTableBets).mockResolvedValue([
      {
        ...locked,
        modeId: "prop_hunt",
        gameId: liveGame.id,
        question: "Who wins?",
        choices: ["Over 250.5", "Under 250.5"],
        picks: [{ userId: user.id, choice: "Over 250.5" }],
      },
    ]);
    renderApp();
    const ticket = await openTicket();
    const now = within(await ticket.findByRole("region", { name: "Right now" }));
    expect(now.getByText("Drake Maye passing yards")).toBeInTheDocument();
    expect(now.getByText("212")).toBeInTheDocument();
    expect(now.getByText("/ 250.5")).toBeInTheDocument();
  });

  it("lets a host override a locked sports bet; it waits on the game otherwise", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 204 }));
    vi.mocked(listTableBets).mockResolvedValue([
      {
        ...locked,
        modeId: "total_disaster",
        gameId: liveGame.id,
        question: "Who wins?",
        choices: ["Over 224.5", "Under 224.5"],
        picks: [{ userId: user.id, choice: "Over 224.5" }],
        viewerCanOverride: true,
      },
    ]);
    renderApp();
    const ticket = await openTicket();
    expect(ticket.getByText("Locked")).toBeInTheDocument();
    expect(ticket.getByText("NYK 77 @ BOS 80 · Q3 5:12")).toBeInTheDocument();
    fireEvent.keyDown(ticket.getByRole("button", { name: "Override" }), { key: "Enter" });
    fireEvent.click(await screen.findByRole("menuitem", { name: "Wash it" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/override`,
        "POST",
        expect.any(String),
        { winningChoice: null },
      ),
    );
  });

  it("sends a 4fun bet when the wager slider is at 0", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(JSON.stringify(bet)));
    renderApp();
    await startU2Pick("For fun?");
    expect(screen.queryByRole("switch", { name: "4fun bet" })).not.toBeInTheDocument();
    const slider = screen.getByRole("slider", { name: "Wager in points" });
    fireEvent.keyDown(slider, { key: "Home" });
    expect(screen.getByText("(4fun)")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    fireEvent.click(await screen.findByRole("button", { name: /Send · 4fun/ }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/tables/${table.id}/bets`,
        "POST",
        expect.any(String),
        expect.objectContaining({ wagerCents: 0, forFun: true }),
      ),
    );
  });

  it("picks after confirming and clears by tapping the pick again, keeping others private", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 204 }));
    vi.mocked(listTableBets)
      .mockResolvedValueOnce([bet])
      .mockResolvedValue([{ ...bet, myPick: "No", myParticipation: "picked", pickCount: 2 }]);
    renderApp();
    const ticket = await openTicket();
    expect(ticket.queryByText(/stay private/)).not.toBeInTheDocument();
    fireEvent.click(ticket.getByRole("button", { name: "No" }));
    expect(await screen.findByText("Pick No?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/pick`,
        "PUT",
        expect.any(String),
        { choice: "No" },
      ),
    );
    const reopened = within(screen.getAllByRole("dialog")[0]!);
    expect(await reopened.findByRole("button", { name: "No", pressed: true })).toBeInTheDocument();
    expect(reopened.queryByText(/\d+ · \d+%/)).not.toBeInTheDocument();
    fireEvent.click(reopened.getByRole("button", { name: "No", pressed: true }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/pick`,
        "DELETE",
        expect.any(String),
        undefined,
      ),
    );
  });

  it("preserves an uncertain proposal and retries the same key after remount", async () => {
    vi.mocked(mutationRequest)
      .mockRejectedValueOnce(new TypeError("disconnected"))
      .mockResolvedValueOnce(new Response(JSON.stringify(bet)));
    const first = renderApp();
    await startU2Pick("Who wins?");
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    fireEvent.click(await screen.findByRole("button", { name: /Send/ }));
    expect(await screen.findByText(/outcome is unknown/)).toBeInTheDocument();
    const key = vi.mocked(mutationRequest).mock.calls[0]?.[2];
    first.unmount();
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(mutationRequest).toHaveBeenCalledTimes(2));
    expect(vi.mocked(mutationRequest).mock.calls[1]?.[2]).toBe(key);
    // Success is silent; the action is done once its saved retry is cleared.
    await waitFor(() => expect(localStorage.getItem(`p2picks:bet-action:${user.id}`)).toBeNull());
  });

  it("disables proposing and picking when betting permission is revoked", async () => {
    vi.mocked(getTable).mockResolvedValue({
      ...table,
      disabledPermissions: { [user.id]: ["bets"] },
    });
    renderApp();
    expect(await screen.findByRole("button", { name: "Bet" })).toBeDisabled();
    const ticket = await openTicket();
    expect(ticket.getByRole("button", { name: "Yes" })).toBeDisabled();
    expect(ticket.getByText(/A host turned off betting for you/)).toBeInTheDocument();
    expect(mutationRequest).not.toHaveBeenCalled();
  });

  it("lets an eligible member call the result or wash a locked bet", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 204 }));
    vi.mocked(listTableBets).mockResolvedValue([{ ...locked, viewerCanCall: true }]);
    renderApp();
    const ticket = await openTicket();
    expect(ticket.getByText("1 · 100%")).toBeInTheDocument();
    expect(ticket.getByRole("button", { name: /Yes/ })).toBeDisabled();
    await callIt("Yes");
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/settle`,
        "POST",
        expect.any(String),
        { winningChoice: "Yes" },
      ),
    );
    // Success is silent; the action is done once its saved retry is cleared.
    await waitFor(() => expect(localStorage.getItem(`p2picks:bet-action:${user.id}`)).toBeNull());
    await callIt("None of these (wash)");
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenLastCalledWith(
        `/bets/${bet.id}/settle`,
        "POST",
        expect.any(String),
        { winningChoice: null },
      ),
    );
  });

  it("says who calls the result when the viewer cannot", async () => {
    vi.mocked(listTableBets).mockResolvedValue([{ ...locked, settledBy: "hosts" }]);
    vi.mocked(getTable).mockResolvedValue({ ...table, hostIds: [table.id] });
    renderApp();
    const ticket = await openTicket();
    expect(ticket.getByText(/Waiting on/)).toHaveTextContent("Waiting on hosts");
    expect(screen.queryByRole("button", { name: "Call it" })).not.toBeInTheDocument();
  });

  it("shows your result on a resolved ticket", async () => {
    route.pathname = "/tickets";
    const resolved = {
      ...locked,
      status: "SETTLED" as const,
      winningChoice: "Yes",
      myPick: "Yes",
      myParticipation: "picked" as const,
      deltasCents: { [user.id]: 150 },
    };
    vi.mocked(listMyBets).mockImplementation(async ({ status }) => ({
      items: status === "SETTLED" ? [resolved] : [],
      nextCursor: null,
    }));
    renderApp();
    // Tickets opens on Active.
    fireEvent.click(await screen.findByRole("tab", { name: "Resolved" }));
    expect(await screen.findByText("You won")).toBeInTheDocument();
    expect(screen.getByText("+1.50 pts")).toBeInTheDocument();
  });

  it("lets a host settle the table's resolved points", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response("{}", { status: 201 }));
    vi.mocked(listTableBets).mockResolvedValue([
      { ...locked, status: "SETTLED", winningChoice: "Yes" },
      { ...locked, id: "123e4567-e89b-42d3-a456-426614174003", status: "WASHED" },
    ]);
    renderApp();
    await openTab("Host");
    expect(await screen.findByText(/1 resolved bet is waiting/)).toBeInTheDocument();
    await confirm("Settle table points");
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/tables/${table.id}/settle-points`,
        "POST",
        expect.any(String),
      ),
    );
  });

  it("hides points settlement from members who are not hosts", async () => {
    vi.mocked(getTable).mockResolvedValue({ ...table, hostIds: [table.id] });
    vi.mocked(listTableBets).mockResolvedValue([{ ...locked, status: "SETTLED" }]);
    renderApp();
    await openTab("About");
    expect(await screen.findByText("Host settings")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Host" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Settle table points" })).not.toBeInTheDocument();
  });

  it("blocks new actions while another tab has an uncertain one", async () => {
    renderApp();
    const ticket = await openTicket();
    expect(ticket.getByRole("button", { name: "Yes" })).toBeEnabled();
    const storageKey = `p2picks:bet-action:${user.id}`;
    const saved = JSON.stringify({
      intent: { kind: "pick", tableId: table.id, betId: bet.id, choice: "Yes" },
      key: "123e4567-e89b-42d3-a456-426614174009",
      createdAt: Date.now(),
    });
    localStorage.setItem(storageKey, saved);
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: storageKey, newValue: saved }));
    });
    expect(
      await screen.findByRole("button", { name: "Retry same action", hidden: true }),
    ).toBeInTheDocument();
    expect(ticket.getByRole("button", { name: "Yes" })).toBeDisabled();
    localStorage.removeItem(storageKey);
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: storageKey, newValue: null }));
    });
    await waitFor(() => expect(ticket.getByRole("button", { name: "Yes" })).toBeEnabled());
  });
});

describe("desktop home", () => {
  it("lists tickets live first and marks tables where you picked", async () => {
    vi.spyOn(window, "matchMedia").mockImplementation(
      (query) =>
        ({
          matches: query === HOME_TICKETS,
          media: query,
          onchange: null,
          addListener: () => {},
          removeListener: () => {},
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
        }) as MediaQueryList,
    );
    route.pathname = "/";
    const picked: Bet = { ...bet, pickCount: 1, myPick: "Yes", myParticipation: "picked" };
    const waiting: Bet = {
      ...locked,
      id: "123e4567-e89b-42d3-a456-426614174003",
      question: "Rain?",
    };
    vi.mocked(listMyBets).mockImplementation(async ({ status }) => ({
      items: status === "ACTIVE" ? [picked] : status === "PENDING" ? [waiting] : [],
      nextCursor: null,
    }));
    renderApp();
    const column = (await screen.findByRole("heading", { name: "Tickets" })).closest("section")!;
    await waitFor(() =>
      expect(
        within(column)
          .getAllByRole("heading", { level: 3 })
          .map((heading) => heading.textContent),
      ).toEqual(["Who wins?", "Rain?"]),
    );
    await waitFor(() =>
      expect(listMyBets).toHaveBeenCalledWith({ status: "WASHED" }, expect.any(AbortSignal)),
    );
    expect(await screen.findByText("In play")).toBeInTheDocument();
    vi.mocked(window.matchMedia).mockRestore();
  });
});

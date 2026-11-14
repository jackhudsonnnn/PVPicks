import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import { getChatUnread, getChatWindow } from "./chat-api";
import { listTableBalances } from "./tables-api";
import {
  currentUser,
  getTable,
  listMyBets,
  listTableBets,
  listTables,
  mutationRequest,
  type BackendBet,
} from "./backend-api";

const route = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: route }),
}));
vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  currentUser: vi.fn(),
  getTable: vi.fn(),
  listTables: vi.fn(),
  listTableBets: vi.fn(),
  listMyBets: vi.fn(),
  mutationRequest: vi.fn(),
}));
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
};

const locked: BackendBet = {
  ...bet,
  status: "PENDING",
  settledBy: "hosts",
  pickCount: 1,
  picks: [{ userId: user.id, choice: "Yes" }],
  noGuessUserIds: [],
  remainderRule: "host",
};

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <BackendApp />
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
  vi.mocked(currentUser).mockResolvedValue(user);
  vi.mocked(getTable).mockResolvedValue(table);
  vi.mocked(listTableBets).mockResolvedValue([bet]);
  vi.mocked(listTables).mockResolvedValue([table]);
  vi.mocked(listTableBalances).mockResolvedValue([]);
  vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
  vi.mocked(getChatWindow).mockResolvedValue({ items: [], previousCursor: null, nextCursor: null });
});

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
  it("shows sports modes as coming soon and sends a U2Pick from the preview", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(JSON.stringify(bet)));
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Bet" }));
    expect(screen.getByRole("button", { name: /Either Or/ })).toBeDisabled();
    expect(screen.getAllByText("Coming soon")).toHaveLength(7);
    fireEvent.click(screen.getByRole("button", { name: /U2Pick/ }));
    expect(screen.getByRole("button", { name: "Preview ticket" })).toBeDisabled();
    expect(screen.getByText("Write what you're betting on.")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: " Who wins? " } });
    fireEvent.click(screen.getByRole("button", { name: "Add 1.00 points" }));
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
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
  });

  it("sends a 4fun bet with no wager", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(JSON.stringify(bet)));
    renderApp();
    await startU2Pick("For fun?");
    fireEvent.click(screen.getByRole("switch", { name: "4fun bet" }));
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
    expect(ticket.getByText("Picks stay private until lock")).toBeInTheDocument();
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
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
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
    vi.mocked(listTableBets).mockResolvedValue([locked]);
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
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
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
      counts: { ACTIVE: 0, PENDING: 0, SETTLED: 1, WASHED: 0 },
    }));
    renderApp();
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

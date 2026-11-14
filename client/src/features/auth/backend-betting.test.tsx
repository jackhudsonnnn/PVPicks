import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import {
  currentUser,
  getBet,
  getTable,
  listTableBets,
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
  listTableBets: vi.fn(),
  getBet: vi.fn(),
  mutationRequest: vi.fn(),
}));

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "host", hue: 1 };
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
  locksAt: "2026-11-11T12:00:00Z",
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

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  route.pathname = `/tables/${table.id}`;
  vi.mocked(currentUser).mockResolvedValue(user);
  vi.mocked(getTable).mockResolvedValue(table);
  vi.mocked(listTableBets).mockResolvedValue([bet]);
  vi.mocked(getBet).mockResolvedValue(bet);
});
afterEach(() => vi.unstubAllGlobals());

describe("U2Pick betting", () => {
  it("validates and creates a U2Pick with the supplied settings", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(JSON.stringify(bet)));
    renderApp();
    fireEvent.click(await screen.findByText("Create a U2Pick"));
    fireEvent.click(screen.getByRole("button", { name: "Create bet" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Write what");
    expect(mutationRequest).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: " Who wins? " } });
    fireEvent.change(screen.getByLabelText("Option 1"), { target: { value: "Yes" } });
    fireEvent.change(screen.getByLabelText("Option 2"), { target: { value: "No" } });
    fireEvent.click(screen.getByRole("button", { name: "Create bet" }));
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
        wagerCents: 100,
        durationSec: 60,
        forFun: false,
        settledBy: "hosts",
      },
    );
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
  });

  it("picks and clears only the viewer's selection while keeping others private", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 204 }));
    vi.mocked(getBet)
      .mockResolvedValueOnce(bet)
      .mockResolvedValue({ ...bet, myPick: "No", myParticipation: "picked", pickCount: 2 });
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByText(/picks stay private/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "No" }));
    fireEvent.click(screen.getByRole("button", { name: "Save pick" }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/pick`,
        "PUT",
        expect.any(String),
        { choice: "No" },
      ),
    );
    expect(await screen.findByText("Your pick: No")).toBeInTheDocument();
    expect(screen.queryByText("Picks after lock")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear my pick" }));
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
    fireEvent.click(await screen.findByText("Create a U2Pick"));
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: "Who wins?" } });
    fireEvent.change(screen.getByLabelText("Option 1"), { target: { value: "Yes" } });
    fireEvent.change(screen.getByLabelText("Option 2"), { target: { value: "No" } });
    fireEvent.click(screen.getByRole("button", { name: "Create bet" }));
    expect(await screen.findByText(/outcome is unknown/)).toBeInTheDocument();
    const key = vi.mocked(mutationRequest).mock.calls[0]?.[2];
    first.unmount();
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(mutationRequest).toHaveBeenCalledTimes(2));
    expect(vi.mocked(mutationRequest).mock.calls[1]?.[2]).toBe(key);
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
  });

  it("disables creation and picking when betting permission is revoked", async () => {
    vi.mocked(getTable).mockResolvedValue({
      ...table,
      disabledPermissions: { [user.id]: ["bets"] },
    });
    renderApp();
    fireEvent.click(await screen.findByText("Create a U2Pick"));
    expect(screen.getByRole("button", { name: "Create bet" })).toBeDisabled();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByRole("button", { name: "Save pick" })).toBeDisabled();
    expect(mutationRequest).not.toHaveBeenCalled();
  });

  it("lets an eligible member call the result or wash a locked bet", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 204 }));
    vi.mocked(listTableBets).mockResolvedValue([locked]);
    vi.mocked(getBet).mockResolvedValue(locked);
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(screen.queryByRole("button", { name: "Save pick" })).not.toBeInTheDocument();
    await confirm("Call result");
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith(
        `/bets/${bet.id}/settle`,
        "POST",
        expect.any(String),
        { winningChoice: "Yes" },
      ),
    );
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Wash" }));
    await confirm("Call result");
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenLastCalledWith(
        `/bets/${bet.id}/settle`,
        "POST",
        expect.any(String),
        { winningChoice: null },
      ),
    );
  });

  it("explains who calls the result when the viewer cannot", async () => {
    vi.mocked(listTableBets).mockResolvedValue([
      { ...locked, settledBy: "me", proposerId: table.id },
    ]);
    vi.mocked(getBet).mockResolvedValue({ ...locked, settledBy: "me", proposerId: table.id });
    renderApp();
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByText(/Only the person who proposed it calls it/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Call result" })).not.toBeInTheDocument();
  });

  it("lets a host settle the table's resolved points", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response("{}", { status: 201 }));
    vi.mocked(listTableBets).mockResolvedValue([
      { ...locked, status: "SETTLED", winningChoice: "Yes" },
      { ...locked, id: "123e4567-e89b-42d3-a456-426614174003", status: "WASHED" },
    ]);
    renderApp();
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
    vi.mocked(getTable).mockResolvedValue({ ...table, hostIds: [] });
    vi.mocked(listTableBets).mockResolvedValue([{ ...locked, status: "SETTLED" }]);
    renderApp();
    expect(await screen.findByText("Who wins?")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Settle table points" })).not.toBeInTheDocument();
  });

  it("blocks new actions while another tab has an uncertain one", async () => {
    renderApp();
    fireEvent.click(await screen.findByText("Create a U2Pick"));
    expect(screen.getByRole("button", { name: "Create bet" })).toBeEnabled();
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
    expect(await screen.findByRole("button", { name: "Retry same action" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create bet" })).toBeDisabled();
    localStorage.removeItem(storageKey);
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: storageKey, newValue: null }));
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Create bet" })).toBeEnabled());
  });
});

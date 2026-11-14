import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import { useIsolatedBackend } from "./backend-integration-utils";

const route = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: route }),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

async function signIn(username: string) {
  fireEvent.change(await screen.findByLabelText("Username"), { target: { value: username } });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "a secure test password" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByRole("button", { name: "Bet" })).toBeInTheDocument();
}

function renderTable() {
  const tableId = process.env["PVPICKS_TEST_TABLE_ID"];
  if (!tableId) throw new Error("Missing test table ID");
  route.pathname = `/tables/${tableId}`;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <BackendApp />
    </QueryClientProvider>,
  );
}

async function confirm(label: string) {
  fireEvent.click(await screen.findByRole("button", { name: label }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
}

// BettingFrontendIntegrationTest runs this file twice: "pick" before the backend locks the bet,
// then "result" after it.
const phase = process.env["PVPICKS_TEST_PORT"] ? process.env["PVPICKS_TEST_PHASE"] : undefined;

/** Opens the bet from the felt's deck and returns the ticket dialog. */
async function openTicket() {
  fireEvent.click(await screen.findByRole("button", { name: "Open ticket: Cats or dogs?" }));
  return within(await screen.findByRole("dialog"));
}

async function pick(choice: string) {
  const ticket = await openTicket();
  fireEvent.click(ticket.getByRole("button", { name: choice }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
  const reopened = within(screen.getAllByRole("dialog")[0]!);
  expect(await reopened.findByRole("button", { name: choice, pressed: true })).toBeInTheDocument();
  fireEvent.keyDown(screen.getAllByRole("dialog")[0]!, { key: "Escape" });
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
}

describe.runIf(phase === "pick")("isolated U2Pick integration: proposal and picks", () => {
  it("creates a bet and keeps two users' active picks private", async () => {
    useIsolatedBackend();
    renderTable();
    await signIn("betuihost");
    fireEvent.click(screen.getByRole("button", { name: "Bet" }));
    fireEvent.click(await screen.findByRole("button", { name: /U2Pick/ }));
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: "Cats or dogs?" } });
    fireEvent.change(screen.getByLabelText("Option 1"), { target: { value: "Cats" } });
    fireEvent.change(screen.getByLabelText("Option 2"), { target: { value: "Dogs" } });
    fireEvent.click(screen.getByRole("tab", { name: "10m" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview ticket" }));
    fireEvent.click(await screen.findByRole("button", { name: /Send/ }));
    expect((await openTicket()).getByText(/Tap a choice to join/)).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await pick("Cats");
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await signIn("betuiplayer");
    const ticket = await openTicket();
    expect(ticket.getByText(/Tap a choice to join/)).toBeInTheDocument();
    // The host's pick stays private: no pressed choice and no tallies before lock.
    expect(ticket.queryByRole("button", { pressed: true })).not.toBeInTheDocument();
    expect(ticket.queryByText(/\d+ · \d+%/)).not.toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await pick("Dogs");
  }, 30000);
});

describe.runIf(phase === "result")("isolated U2Pick integration: result and points", () => {
  it("lets the host call the result and settle the table's points", async () => {
    useIsolatedBackend();
    renderTable();
    await signIn("betuihost");
    const ticket = await openTicket();
    expect(await ticket.findAllByText("1 · 50%")).toHaveLength(2);
    const trigger = await screen.findByRole("button", { name: "Call it" });
    fireEvent.keyDown(trigger, { key: "Enter" });
    fireEvent.click(await screen.findByRole("menuitem", { name: "Cats" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    // The open ticket shows the result; resolved bets leave the deck, and points settlement is in
    // the Host panel.
    expect(
      await within(screen.getAllByRole("dialog")[0]!).findByText("You won"),
    ).toBeInTheDocument();
    fireEvent.keyDown(screen.getAllByRole("dialog")[0]!, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click((await screen.findAllByRole("tab", { name: "Host" }))[0]!);
    expect(await screen.findByText(/1 resolved bet is waiting/)).toBeInTheDocument();
    await confirm("Settle table points");
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Settle table points" })).not.toBeInTheDocument(),
    );
  }, 30000);
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import { useIsolatedBackend } from "./backend-integration-utils";

const route = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
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
  expect(await screen.findByText("Create a U2Pick")).toBeInTheDocument();
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

describe.runIf(phase === "pick")("isolated U2Pick integration: proposal and picks", () => {
  it("creates a bet and keeps two users' active picks private", async () => {
    useIsolatedBackend();
    renderTable();
    await signIn("betuihost");
    fireEvent.click(screen.getByText("Create a U2Pick"));
    fireEvent.change(screen.getByLabelText("Bet question"), { target: { value: "Cats or dogs?" } });
    fireEvent.change(screen.getByLabelText("Option 1"), { target: { value: "Cats" } });
    fireEvent.change(screen.getByLabelText("Option 2"), { target: { value: "Dogs" } });
    fireEvent.change(screen.getByLabelText(/Pick window/), { target: { value: "600" } });
    fireEvent.click(screen.getByRole("button", { name: "Create bet" }));
    expect(await screen.findByText("Cats or dogs?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "View details" }));
    expect(await screen.findByText("You have not joined.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save pick" }));
    expect(await screen.findByText("Your pick: Cats")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await signIn("betuiplayer");
    expect(await screen.findByText(/1 pick ·/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "View details" }));
    expect(await screen.findByText("You have not joined.")).toBeInTheDocument();
    expect(screen.queryByText("Your pick: Cats")).not.toBeInTheDocument();
    expect(screen.queryByText("Picks after lock")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Dogs" }));
    fireEvent.click(screen.getByRole("button", { name: "Save pick" }));
    expect(await screen.findByText("Your pick: Dogs")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/2 picks ·/)).toBeInTheDocument());
  }, 30000);
});

describe.runIf(phase === "result")("isolated U2Pick integration: result and points", () => {
  it("lets the host call the result and settle the table's points", async () => {
    useIsolatedBackend();
    renderTable();
    await signIn("betuihost");
    fireEvent.click(await screen.findByRole("button", { name: "View details" }));
    expect(await screen.findByText("Picks after lock")).toBeInTheDocument();
    expect(await screen.findByText("You: Cats")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Cats" }));
    await confirm("Call result");
    expect(await screen.findByText("Winning choice: Cats")).toBeInTheDocument();
    expect(await screen.findByText(/1 resolved bet is waiting/)).toBeInTheDocument();
    await confirm("Settle table points");
    expect(await screen.findByText("Table points settlement: Complete")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Settle table points" })).not.toBeInTheDocument();
  }, 30000);
});

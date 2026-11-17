import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { createPlayer, issuePlayerCode, listPlayers } from "./api";
import { Players } from "./players";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listPlayers: vi.fn(),
  createPlayer: vi.fn(),
  issuePlayerCode: vi.fn(),
}));

const admin = { id: "123e4567-e89b-42d3-a456-426614174000", username: "admin", hue: 4 };
const later = "2026-11-16T20:00:00Z";
const player = (username: string, passwordSet: boolean) => ({
  id: `123e4567-e89b-42d3-a456-42661417400${username.length}`,
  username,
  status: "ACTIVE" as const,
  passwordSet,
  codeExpiresAt: passwordSet ? null : later,
  createdAt: "2026-11-15T20:00:00Z",
});

function renderPlayers() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <Players user={admin} />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.resetAllMocks());

describe("admin players", () => {
  it("creates a player and shows the one-time setup link with a QR code", async () => {
    vi.mocked(listPlayers).mockResolvedValue({ items: [player("dana", true)], nextCursor: null });
    vi.mocked(createPlayer).mockResolvedValue({
      id: "123e4567-e89b-42d3-a456-426614174009",
      username: "newbie",
      setupCode: "AbCdEfGhIjKlMnOpQrStUv",
      purpose: "SETUP",
      expiresAt: later,
    });
    renderPlayers();
    expect(await screen.findByText("dana")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Reset link/ })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("New player's username"), {
      target: { value: "newbie" },
    });
    fireEvent.click(screen.getByRole("button", { name: /Create/ }));
    expect(
      await screen.findByText(`${window.location.origin}/setup#AbCdEfGhIjKlMnOpQrStUv`),
    ).toBeInTheDocument();
    expect(await screen.findByAltText("Setup link for newbie")).toBeInTheDocument();
    expect(createPlayer).toHaveBeenCalledWith("newbie");
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    expect(screen.queryByText(/setup#/)).not.toBeInTheDocument();
  });

  it("issues a reset link and explains a taken username", async () => {
    vi.mocked(listPlayers).mockResolvedValue({ items: [player("dana", true)], nextCursor: null });
    vi.mocked(issuePlayerCode).mockResolvedValue({
      setupCode: "ZyXwVuTsRqPoNmLkJiHgFe",
      purpose: "RESET",
      expiresAt: later,
    });
    vi.mocked(createPlayer).mockRejectedValue(
      new BackendError("Backend request failed.", 409, "username_taken"),
    );
    renderPlayers();
    fireEvent.click(await screen.findByRole("button", { name: /Reset link/ }));
    expect(await screen.findByText("Reset link for dana")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("New player's username"), { target: { value: "dana" } });
    fireEvent.click(screen.getByRole("button", { name: /Create/ }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("That username is taken."),
    );
  });

  it("loads the next page of players from the server", async () => {
    vi.mocked(listPlayers).mockImplementation(async (cursor) =>
      cursor === null
        ? { items: [player("dana", true)], nextCursor: "page-2" }
        : { items: [player("eli", false)], nextCursor: null },
    );
    renderPlayers();
    expect(await screen.findByText("dana")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText("eli")).toBeInTheDocument();
    expect(listPlayers).toHaveBeenLastCalledWith("page-2", expect.anything());
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });
});

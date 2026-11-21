import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import {
  createPlayer,
  deletePlayer,
  getLanParty,
  getPlayerCode,
  listPlayers,
  rotatePlayerCode,
} from "./api";
import { getAppMode } from "@/lib/app-mode-api";
import { PlayerList } from "./players";
import { clearSignInLockout, getSignInLockouts } from "./dashboard-api";

vi.mock("./dashboard-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./dashboard-api")>()),
  getSignInLockouts: vi.fn(),
  clearSignInLockout: vi.fn(),
}));

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listPlayers: vi.fn(),
  createPlayer: vi.fn(),
  getPlayerCode: vi.fn(),
  rotatePlayerCode: vi.fn(),
  deletePlayer: vi.fn(),
  getLanParty: vi.fn(),
}));
vi.mock("@/lib/app-mode-api", () => ({ getAppMode: vi.fn() }));

const admin = { id: "123e4567-e89b-42d3-a456-426614174000", username: "admin", hue: 4 };
const player = (username: string, signedIn: boolean, strikes = 0) => ({
  id: `123e4567-e89b-42d3-a456-42661417400${username.length}`,
  username,
  hue: 2,
  status: "ACTIVE" as const,
  lastSignInAt: signedIn ? "2026-11-16T20:00:00Z" : null,
  codeRotatedAt: "2026-11-15T20:00:00Z",
  createdAt: "2026-11-15T20:00:00Z",
  strikes,
  openReports: strikes,
});

/** The page holds the selection (the URL, in the app); this does the same so select and show stay one rule. */
function Harness({ onOpen }: { onOpen: (id: string | null) => void }) {
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <PlayerList
      user={admin}
      selected={selected}
      onOpen={(id) => {
        setSelected(id);
        onOpen(id);
      }}
      onClose={() => {
        setSelected(null);
        onOpen(null);
      }}
    />
  );
}

function renderPlayers(onOpen = vi.fn()) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <Harness onOpen={onOpen} />
    </QueryClientProvider>,
  );
  return onOpen;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getAppMode).mockResolvedValue("dev");
  vi.mocked(getSignInLockouts).mockResolvedValue({
    playerLimit: 10,
    addressLimit: 100,
    address: { wrongCodes: 0, locked: false, resetsAt: null },
    players: [],
  });
});

describe("admin players", () => {
  it("keeps codes blurred, shows one at a time with the player's audit, and hides on a tap", async () => {
    vi.mocked(listPlayers).mockResolvedValue({
      items: [player("dana", true), player("eli", false)],
      nextCursor: null,
    });
    vi.mocked(getPlayerCode).mockImplementation(async (id) =>
      id === player("dana", true).id ? "K7QM" : "E2LI",
    );
    const onOpen = renderPlayers();
    expect(await screen.findByText("dana")).toBeInTheDocument();
    // Blurred by default: the real code isn't even fetched.
    expect(screen.getAllByLabelText("Code hidden")).toHaveLength(2);
    expect(getPlayerCode).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Show dana's code" }));
    expect(await screen.findByText("K7QM")).toBeInTheDocument();
    expect(await screen.findByAltText("Sign-in QR code for dana")).toBeInTheDocument();
    expect(onOpen).toHaveBeenLastCalledWith(player("dana", true).id);
    // Showing eli's blurs dana's.
    fireEvent.click(screen.getByRole("button", { name: "Show eli's code" }));
    expect(await screen.findByText("E2LI")).toBeInTheDocument();
    expect(screen.queryByText("K7QM")).not.toBeInTheDocument();
    expect(onOpen).toHaveBeenLastCalledWith(player("eli", false).id);
    // Tapping the shown QR blurs it again and deselects.
    fireEvent.click(screen.getByRole("button", { name: "Hide eli's code" }));
    expect(screen.queryByText("E2LI")).not.toBeInTheDocument();
    expect(screen.getAllByLabelText("Code hidden")).toHaveLength(2);
    expect(onOpen).toHaveBeenLastCalledWith(null);
    // Selecting by name shows the QR too.
    fireEvent.click(screen.getByRole("button", { name: /^dana/ }));
    expect(await screen.findByText("K7QM")).toBeInTheDocument();
  });

  it("copies the sign-in link without showing the code", async () => {
    const dana = player("dana", true);
    vi.mocked(listPlayers).mockResolvedValue({ items: [dana], nextCursor: null });
    vi.mocked(getPlayerCode).mockResolvedValue("K7QM");
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    renderPlayers();
    fireEvent.click(await screen.findByRole("button", { name: "Copy dana's sign-in link" }));
    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/signin#${dana.id}.K7QM`),
    );
    expect(screen.getByLabelText("Code hidden")).toBeInTheDocument();
    expect(screen.queryByText("K7QM")).not.toBeInTheDocument();
  });

  it("rotates a code after asking, and shows the new one", async () => {
    vi.mocked(listPlayers).mockResolvedValue({ items: [player("dana", true)], nextCursor: null });
    vi.mocked(rotatePlayerCode).mockResolvedValue("ZX9P");
    renderPlayers();
    fireEvent.click(await screen.findByRole("button", { name: "Rotate" }));
    expect(await screen.findByText("Give dana a new code?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    expect(await screen.findByText("ZX9P")).toBeInTheDocument();
    expect(rotatePlayerCode).toHaveBeenCalledWith(player("dana", true).id);
  });

  it("creates a player whose card has their code, and explains a taken username", async () => {
    const newbie = { ...player("newbie", false), id: "123e4567-e89b-42d3-a456-426614174009" };
    vi.mocked(listPlayers)
      .mockResolvedValueOnce({ items: [player("dana", true)], nextCursor: null })
      .mockResolvedValue({ items: [newbie, player("dana", true)], nextCursor: null });
    vi.mocked(createPlayer).mockResolvedValue({ id: newbie.id, username: "newbie", code: "NB42" });
    renderPlayers();
    expect(await screen.findByText("dana")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /New player/ }));
    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "newbie" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("newbie")).toBeInTheDocument();
    expect(createPlayer).toHaveBeenCalledWith("newbie");
    fireEvent.click(screen.getByRole("button", { name: "Show newbie's code" }));
    // Primed from the create response: no extra request.
    expect(await screen.findByText("NB42")).toBeInTheDocument();
    expect(getPlayerCode).not.toHaveBeenCalled();

    vi.mocked(createPlayer).mockRejectedValue(
      new BackendError("Backend request failed.", 409, "username_taken"),
    );
    fireEvent.click(screen.getByRole("button", { name: /New player/ }));
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "dana" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("That username is taken."),
    );
  });

  it("searches by username, flags strikes and opens a player's audit", async () => {
    vi.mocked(listPlayers).mockImplementation(async (_cursor, _signal, search = "") =>
      search === "da"
        ? { items: [player("dana", true, 2)], nextCursor: null }
        : { items: [player("dana", true, 2), player("eli", false)], nextCursor: null },
    );
    const onOpen = renderPlayers();
    expect(await screen.findByText("eli")).toBeInTheDocument();
    expect(screen.getByText("2/3")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search players"), { target: { value: "DA" } });
    await waitFor(() => expect(screen.queryByText("eli")).not.toBeInTheDocument());
    expect(listPlayers).toHaveBeenLastCalledWith(null, expect.anything(), "da");
    fireEvent.click(await screen.findByRole("button", { name: /^dana/ }));
    expect(onOpen).toHaveBeenCalledWith(player("dana", true).id);
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
    expect(listPlayers).toHaveBeenLastCalledWith("page-2", expect.anything(), "");
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("marks a locked-out player's card and unlocks them there", async () => {
    const dana = player("dana", true);
    vi.mocked(listPlayers).mockResolvedValue({ items: [dana], nextCursor: null });
    vi.mocked(getSignInLockouts).mockResolvedValue({
      playerLimit: 10,
      addressLimit: 100,
      address: { wrongCodes: 10, locked: false, resetsAt: null },
      players: [
        { id: dana.id, username: "dana", hue: 2, wrongCodes: 10, locked: true, resetsAt: null },
      ],
    });
    vi.mocked(clearSignInLockout).mockResolvedValue();
    renderPlayers();
    expect(await screen.findByText("Locked out")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Unlock dana" }));
    await waitFor(() => expect(clearSignInLockout).toHaveBeenCalledWith(dana.id));
  });

  it("deletes a player after a warning about their tables, and says how many bets washed", async () => {
    vi.mocked(listPlayers).mockResolvedValue({ items: [player("dana", true)], nextCursor: null });
    vi.mocked(deletePlayer).mockResolvedValue(2);
    renderPlayers();
    fireEvent.click(await screen.findByRole("button", { name: "Delete account" }));
    expect(
      await screen.findByText(/every open bet they're in washes for everyone/),
    ).toBeInTheDocument();
    expect(deletePlayer).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(deletePlayer).toHaveBeenCalledWith(player("dana", true).id));
    await waitFor(() => expect(listPlayers).toHaveBeenCalledTimes(2));
    expect(getLanParty).not.toHaveBeenCalled();
  });

  it("shows the LAN party table and who's at it", async () => {
    vi.mocked(getAppMode).mockResolvedValue("lan");
    const host = player("dana", true);
    const guest = player("eli", false);
    vi.mocked(listPlayers).mockResolvedValue({
      items: [guest, host, player("frank", false)],
      nextCursor: null,
    });
    vi.mocked(getLanParty).mockResolvedValue({
      id: "123e4567-e89b-42d3-a456-426614174099",
      name: "New Table",
      emoji: "🏈",
      deleted: false,
      hostIds: [host.id],
      memberIds: [host.id, guest.id],
    });
    renderPlayers();
    expect(await screen.findByText(/🏈 New Table/)).toBeInTheDocument();
    expect(screen.getByText(/2 players/)).toBeInTheDocument();
    expect(screen.getByText("Host")).toBeInTheDocument();
    expect(screen.getAllByText("At the table")).toHaveLength(1);
  });

  it("says when there's no party table yet", async () => {
    vi.mocked(getAppMode).mockResolvedValue("lan");
    vi.mocked(listPlayers).mockResolvedValue({ items: [], nextCursor: null });
    vi.mocked(getLanParty).mockResolvedValue(null);
    renderPlayers();
    expect(await screen.findByText(/The next player you create hosts it/)).toBeInTheDocument();
  });
});

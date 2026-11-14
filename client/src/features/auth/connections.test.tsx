import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsProvider } from "@/features/settings/settings-provider";
import { currentUser, listTables, mutationRequest } from "./backend-api";
import { BackendApp } from "./backend-app";
import { ArchivedTable, ArchivedTables } from "./backend-archives";
import { BackendGuide } from "./backend-guide";
import { BackendPreferences } from "./backend-preferences";
import { getChatUnread, getChatWindow } from "./chat-api";
import { getDebt, getDebtCap, getInvitePreferences } from "./preferences-api";
import { listArchives } from "./tables-api";

const route = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({
    select,
  }: {
    select: (state: { location: { pathname: string; hash: string } }) => string;
  }) => select({ location: { ...route, hash: "" } }),
}));
vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  currentUser: vi.fn(),
  listTables: vi.fn(),
  mutationRequest: vi.fn(),
}));
vi.mock("./tables-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./tables-api")>()),
  listArchives: vi.fn(),
}));
vi.mock("./chat-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-api")>()),
  getChatWindow: vi.fn(),
  getChatUnread: vi.fn(),
  markRead: vi.fn(),
}));
vi.mock("./preferences-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./preferences-api")>()),
  getInvitePreferences: vi.fn(),
  getDebtCap: vi.fn(),
  getDebt: vi.fn(),
}));
const feeds = vi.hoisted(() => [] as (string | null)[]);
vi.mock("./realtime", () => ({
  RealtimeProvider: ({ children }: { children: ReactNode }) => children,
  useRealtimeStatus: () => null,
  useRealtimeFeed: (destination: string | null) => {
    feeds.push(destination);
  },
}));

const player = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const archive = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  name: "Old Table",
  emoji: "🍺",
  archivedAt: "2026-11-10T12:00:00Z",
  expiresAt: "2027-02-08T12:00:00Z",
};

function wrap(children: ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SettingsProvider>{children}</SettingsProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  feeds.length = 0;
  vi.clearAllMocks();
  route.pathname = "/";
  document.documentElement.classList.remove("light");
});
afterEach(() => vi.unstubAllGlobals());

describe("admin accounts", () => {
  it("see the admin view with live health instead of player screens", async () => {
    vi.mocked(currentUser).mockResolvedValue({ ...player, username: "boss", role: "ADMIN" });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        Response.json({
          checkedAt: "2026-11-13T12:00:00Z",
          postgres: "UP",
          redis: "DOWN",
          kafka: "NOT_CHECKED",
        }),
      ),
    );
    wrap(<BackendApp />);
    expect(await screen.findByRole("heading", { name: "Site health" })).toBeInTheDocument();
    expect(await screen.findByText("Up")).toBeInTheDocument();
    expect(screen.getByText("DOWN")).toBeInTheDocument();
    expect(listTables).not.toHaveBeenCalled();
  });
});

describe("guide feedback", () => {
  it("sends feedback to the backend and thanks the player", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 201 }));
    wrap(<BackendGuide user={player} expire={() => {}} />);
    fireEvent.change(screen.getByLabelText("Your feedback"), {
      target: { value: "  A bet on who scores first, please.  " },
    });
    fireEvent.click(screen.getByRole("button", { name: /Send feedback/ }));
    await waitFor(() =>
      expect(mutationRequest).toHaveBeenCalledWith("/feedback", "POST", expect.any(String), {
        kind: "mode",
        text: "A bet on who scores first, please.",
      }),
    );
    expect(await screen.findByText(/Thanks, it's in/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Send more" }));
    expect(screen.getByLabelText("Your feedback")).toBeInTheDocument();
  });
});

describe("table archives", () => {
  it("lists deleted tables and shows their chat read-only, without a live feed", async () => {
    vi.mocked(listArchives).mockResolvedValue([archive]);
    vi.mocked(getChatWindow).mockResolvedValue({
      items: [
        {
          id: "123e4567-e89b-42d3-a456-426614174002",
          tableId: archive.id,
          kind: "text",
          authorId: player.id,
          text: "last words",
          createdAt: "2026-11-10T11:00:00Z",
        },
      ],
      previousCursor: null,
      nextCursor: "after",
    });
    wrap(
      <>
        <ArchivedTables user={player} />
        <ArchivedTable user={player} tableId={archive.id} expire={() => {}} />
      </>,
    );
    expect(await screen.findByRole("heading", { name: "Deleted tables" })).toBeInTheDocument();
    expect(await screen.findByText("last words")).toBeInTheDocument();
    expect(screen.queryByLabelText("Message")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add a reaction" })).not.toBeInTheDocument();
    expect(feeds.every((destination) => destination === null)).toBe(true);
    // Read positions exist only for live tables.
    expect(getChatUnread).not.toHaveBeenCalled();
  });
});

describe("display settings", () => {
  it("apply the chosen theme to the page", async () => {
    vi.mocked(getInvitePreferences).mockResolvedValue({ friends: "open", following: "request" });
    vi.mocked(getDebtCap).mockResolvedValue(null);
    vi.mocked(getDebt).mockResolvedValue(0);
    wrap(<BackendPreferences user={player} expire={() => {}} />);
    fireEvent.click(screen.getByRole("tab", { name: "Display" }));
    fireEvent.click(await screen.findByRole("tab", { name: "Light" }));
    await waitFor(() => expect(document.documentElement.classList.contains("light")).toBe(true));
  });

  it("keep the saved theme across a reload under StrictMode", async () => {
    localStorage.setItem("p2picks.display", JSON.stringify({ theme: "light" }));
    render(
      <StrictMode>
        <SettingsProvider>
          <p>page</p>
        </SettingsProvider>
      </StrictMode>,
    );
    await waitFor(() => expect(document.documentElement.classList.contains("light")).toBe(true));
    expect(JSON.parse(localStorage.getItem("p2picks.display")!)).toMatchObject({ theme: "light" });
  });
});

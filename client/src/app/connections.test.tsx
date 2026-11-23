import { renderWithQuery } from "@/test/render";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsProvider } from "@/features/preferences/settings-provider";
import { currentUser } from "@/features/auth/api";
import { listTables, listArchives } from "@/features/tables/api";
import { mutationRequest } from "@/lib/api-client";
import { App } from "./app";
import { ArchivedTable, ArchivedTables } from "@/features/tables/components/archives";
import { GuidePage } from "@/features/guide/guide";
import { PreferencesPage } from "@/features/preferences/preferences";
import { getChatUnread, getChatWindow } from "@/features/chat/api";
import { getDebt, getDebtCap, getInvitePreferences } from "@/features/preferences/api";

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
vi.mock("@/features/auth/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/auth/api")>()),
  currentUser: vi.fn(),
}));
vi.mock("@/features/tables/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/tables/api")>()),
  listTables: vi.fn(),
  listArchives: vi.fn(),
}));
vi.mock("@/lib/api-client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api-client")>()),
  mutationRequest: vi.fn(),
}));
vi.mock("@/features/chat/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/chat/api")>()),
  getChatWindow: vi.fn(),
  getChatUnread: vi.fn(),
  markRead: vi.fn(),
}));
vi.mock("@/features/preferences/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/preferences/api")>()),
  getInvitePreferences: vi.fn(),
  getDebtCap: vi.fn(),
  getDebt: vi.fn(),
}));
const feeds = vi.hoisted(() => [] as (string | null)[]);
vi.mock("@/lib/realtime", () => ({
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
  return renderWithQuery(children, { settings: true });
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
    const stats = {
      requests: 0,
      serverErrors: 0,
      clientErrors: 0,
      rateLimited: 0,
      avgMs: null,
      p50: null,
      p95: null,
      p99: null,
      maxMs: 0,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/admin/health"))
          return Response.json({
            checkedAt: "2026-11-13T12:00:00Z",
            postgres: "UP",
            redis: "DOWN",
            kafka: "NOT_CHECKED",
          });
        if (url.includes("/admin/perf/live"))
          return Response.json({
            checkedAt: "2026-11-13T12:00:00Z",
            startedAt: "2026-11-13T11:00:00Z",
            uptimeSeconds: 3600,
            host: "box",
            version: null,
            database: { active: 1, idle: 9, waiting: 0, max: 10 },
            jvm: {
              heapUsedBytes: 100,
              heapMaxBytes: 1000,
              threads: 40,
              processCpu: 0.1,
              processors: 8,
              javaVersion: "21",
            },
            outbox: { pending: 0, oldestPendingSeconds: null },
            feeds: [],
          });
        if (url.includes("/admin/perf"))
          return Response.json({
            window: "1h",
            source: "server",
            from: "2026-11-13T11:00:00Z",
            to: "2026-11-13T12:00:00Z",
            stepSeconds: 60,
            method: null,
            route: null,
            totals: stats,
            series: [],
            routes: [],
          });
        return Response.json({ items: [], nextCursor: null });
      }),
    );
    wrap(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Perf" }));
    expect(await screen.findByRole("tablist", { name: "Source" })).toBeInTheDocument();
    const postgres = await screen.findByText("PostgreSQL");
    expect(postgres.parentElement).toHaveTextContent("Up");
    expect(screen.getByText("Redis").parentElement).toHaveTextContent("Down");
    expect(listTables).not.toHaveBeenCalled();
  });
});

describe("guide feedback", () => {
  it("sends feedback to the backend and thanks the player", async () => {
    vi.mocked(mutationRequest).mockResolvedValue(new Response(null, { status: 201 }));
    wrap(<GuidePage user={player} expire={() => {}} />);
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
    wrap(<PreferencesPage user={player} expire={() => {}} onAccountDeleted={() => {}} />);
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

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendTicker } from "./backend-ticker";
import type { Invalidation } from "./realtime-connection";
import { getTicker, tickerStatus, type TickerGame } from "./ticker-api";

const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("./realtime", () => ({
  useRealtimeFeed: (destination: string, listener: (event: Invalidation) => void) => {
    feeds.set(destination, listener);
  },
}));

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const live: TickerGame = {
  id: "NBA-1",
  league: "NBA",
  status: "LIVE",
  startsAt: "2026-11-13T00:30:00Z",
  period: 3,
  clock: "5:32",
  home: { abbreviation: "BOS", score: 71 },
  away: { abbreviation: "NYK", score: 68 },
  observedAt: "2026-11-13T01:00:00Z",
};

function respond(status: number, body?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () =>
      body === undefined
        ? new Response(null, { status })
        : new Response(JSON.stringify(body), { status }),
    ),
  );
}

function renderTicker() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <BackendTicker user={user} expire={() => {}} />
    </QueryClientProvider>,
  );
}

beforeEach(() => feeds.clear());
afterEach(() => vi.unstubAllGlobals());

describe("ticker API", () => {
  it("returns games, and null rather than an empty list when scores are unavailable", async () => {
    respond(200, { games: [live] });
    await expect(getTicker()).resolves.toEqual([live]);
    respond(503, { code: "feed_unavailable", message: "x" });
    await expect(getTicker()).resolves.toBeNull();
  });

  it("describes each status", () => {
    expect(tickerStatus(live)).toBe("5:32");
    expect(tickerStatus({ ...live, clock: null })).toBe("Period 3");
    expect(tickerStatus({ ...live, status: "FINAL" })).toBe("Final");
    expect(tickerStatus({ ...live, status: "POSTPONED" })).toBe("Postponed");
    expect(tickerStatus({ ...live, status: "SCHEDULED" })).toMatch(/\d{1,2}:30/);
  });
});

describe("ticker strip", () => {
  it("shows scores and refreshes when the ticker feed says they changed", async () => {
    respond(200, { games: [live] });
    renderTicker();
    expect((await screen.findAllByText(/NYK 68/))[0]).toBeInTheDocument();
    respond(200, { games: [{ ...live, home: { abbreviation: "BOS", score: 74 } }] });
    act(() =>
      feeds.get("/user/queue/ticker")?.({
        eventId: "123e4567-e89b-42d3-a456-426614174099",
        type: "ticker.changed",
        schemaVersion: 1,
        occurredAt: "2026-11-13T01:01:00Z",
      }),
    );
    expect((await screen.findAllByText(/BOS 74/))[0]).toBeInTheDocument();
  });

  it("says when scores are unavailable instead of showing no games", async () => {
    respond(503, { code: "feed_unavailable", message: "x" });
    renderTicker();
    expect(await screen.findByText("Live scores are unavailable right now.")).toBeInTheDocument();
  });

  it("says when there are no games", async () => {
    respond(200, { games: [] });
    renderTicker();
    expect(await screen.findByText("No NBA or NFL games today.")).toBeInTheDocument();
  });
});

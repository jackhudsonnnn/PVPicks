import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { devAction, getDevSports } from "./api";
import { DevPage } from "./dev-page";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getDevSports: vi.fn(),
  devAction: vi.fn(),
}));

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "dev", hue: 1 };

function renderPage() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <DevPage user={user} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(devAction).mockResolvedValue();
});

describe("dev data page", () => {
  it("lists scenarios and chunks, starts one and shows a running chunk's position and file errors", async () => {
    vi.mocked(getDevSports).mockResolvedValue({
      speeds: [0.5, 1, 2],
      scenarios: [
        {
          name: "edit-me",
          title: "Edit me",
          description: "Live NFL game",
          league: "NFL",
          running: false,
        },
      ],
      chunks: [
        {
          name: "atl-chi-overtime",
          title: "ATL @ CHI",
          description: "Overtime",
          league: "NBA",
          running: true,
          games: 1,
          players: 0,
          lengthSeconds: 1500,
          positionSeconds: 75,
          speed: 2,
          paused: false,
          error: "Unknown NBA stat",
        },
      ],
    });
    renderPage();
    expect(await screen.findByText("Edit me")).toBeInTheDocument();
    expect(screen.getByText(/1:15 of 25:00/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Unknown NBA stat");
    fireEvent.click(screen.getByRole("button", { name: "Start" }));
    await waitFor(() => expect(devAction).toHaveBeenCalledWith("scenarios", "edit-me", "start"));
    fireEvent.click(screen.getByRole("tab", { name: "0.5×" }));
    await waitFor(() =>
      expect(devAction).toHaveBeenCalledWith("chunks", "atl-chi-overtime", "speed", 0.5),
    );
  });

  it("explains when dev data is off", async () => {
    vi.mocked(getDevSports).mockResolvedValue(null);
    renderPage();
    expect(await screen.findByText(/Dev data is off/)).toBeInTheDocument();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { changeUser, listFlagged, listReports, moderate, type ModerationReport } from "./api";
import { Moderation } from "./moderation";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listReports: vi.fn(),
  listFlagged: vi.fn(),
  moderate: vi.fn(),
  changeUser: vi.fn(),
}));

const admin = { id: "123e4567-e89b-42d3-a456-426614174000", username: "boss", hue: 1 };
const target = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  username: "troll",
  status: "ACTIVE" as const,
  strikes: 2,
};
const report: ModerationReport = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  reason: "harassment",
  note: "keeps at it",
  status: "OPEN",
  createdAt: "2026-11-14T12:00:00Z",
  reporter: {
    id: "123e4567-e89b-42d3-a456-426614174001",
    username: "victim",
    status: "ACTIVE",
    strikes: 0,
  },
  target,
  tableId: "123e4567-e89b-42d3-a456-426614174020",
  tableName: "Game Night",
  messageSnapshot: "you're terrible",
};

function renderQueue() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <Moderation user={admin} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listReports).mockResolvedValue([report]);
  vi.mocked(listFlagged).mockResolvedValue([{ ...target, status: "FROZEN", strikes: 3 }]);
});

describe("moderation", () => {
  it("shows a report with the reported message and warns before a freezing strike", async () => {
    vi.mocked(moderate).mockResolvedValue({ strikes: 3, frozen: true });
    renderQueue();
    expect(await screen.findByText("“you're terrible”")).toBeInTheDocument();
    expect(screen.getByText("at Game Night")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Give a strike" }));
    const dialog = within(await screen.findByRole("alertdialog"));
    expect(dialog.getByText(/third strike/)).toBeInTheDocument();
    fireEvent.click(dialog.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(moderate).toHaveBeenCalledWith("strike", report.id));
  });

  it("dismisses a report, and unfreezes a frozen player", async () => {
    vi.mocked(moderate).mockResolvedValue(null);
    vi.mocked(changeUser).mockResolvedValue();
    renderQueue();
    fireEvent.click(await screen.findByRole("button", { name: "Dismiss" }));
    await waitFor(() => expect(moderate).toHaveBeenCalledWith("dismiss", report.id));
    fireEvent.click(await screen.findByRole("button", { name: "Unfreeze" }));
    await waitFor(() => expect(changeUser).toHaveBeenCalledWith("unfreeze", target.id));
  });
});

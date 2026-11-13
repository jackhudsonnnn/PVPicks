import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError, getProfile } from "./backend-api";
import { BackendSettlements } from "./backend-settlements";
import {
  acknowledgeReview,
  listPendingReviews,
  listSettlementCounterparties,
  listSettlementGroups,
  receiptHistory,
  transitionGroup,
  transitionLine,
  type SettlementGroup,
  type SettlementLine,
} from "./settlement-api";

vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./settlement-api", () => ({
  listPendingReviews: vi.fn(),
  listSettlementCounterparties: vi.fn(),
  listSettlementGroups: vi.fn(),
  receiptHistory: vi.fn(),
  acknowledgeReview: vi.fn(),
  transitionLine: vi.fn(),
  transitionGroup: vi.fn(),
}));

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "testuser", hue: 2 };
const other = "123e4567-e89b-42d3-a456-426614174001";
const lineId = "123e4567-e89b-42d3-a456-426614174002";
const revision = "123e4567-e89b-42d3-a456-426614174003";
const line: SettlementLine = {
  id: lineId,
  settlementId: revision,
  tableId: revision,
  payerId: user.id,
  payeeId: other,
  amountCents: 125n,
  status: "unpaid",
  revision: 1,
  forgiveRequestedAt: null,
  forgiveDeclinedAt: null,
  createdAt: "2026-11-11T12:00:00Z",
  updatedAt: "2026-11-11T12:00:00Z",
};
const group: SettlementGroup = {
  otherUserId: other,
  netCents: -125n,
  revision: "group-revision",
  lines: [line],
  pendingReviewRevision: revision,
};
const review = { otherUserId: other, pendingReviewRevision: revision };
const expire = vi.fn();

function renderScreen() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <BackendSettlements user={user} expire={expire} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  sessionStorage.clear();
  vi.resetAllMocks();
  vi.mocked(getProfile).mockResolvedValue({ id: other, username: "player", hue: 1 });
  vi.mocked(listPendingReviews).mockResolvedValue([review]);
  vi.mocked(listSettlementGroups).mockResolvedValue([group]);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("gated settlement preview", () => {
  it("requires explicit review before payer actions and refreshes the current revision", async () => {
    vi.mocked(acknowledgeReview).mockResolvedValue();
    vi.mocked(listPendingReviews).mockResolvedValueOnce([review]).mockResolvedValue([]);
    vi.mocked(listSettlementGroups)
      .mockResolvedValueOnce([group])
      .mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    renderScreen();
    expect(await screen.findByText(/Balance changed with/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
    fireEvent.click(screen.getByText("Receipt history"));
    expect(screen.getByRole("button", { name: "I sent it" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "I've reviewed this" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Mark group sent" })).toBeEnabled(),
    );
    expect(acknowledgeReview).toHaveBeenCalledWith(other, revision, expect.any(String));
    expect(screen.getByRole("button", { name: "I sent it" })).toBeEnabled();
  });

  it("keeps a closed warning discoverable when group totals overflow", async () => {
    vi.mocked(listSettlementGroups).mockRejectedValue(
      new BackendError("overflow", 409, "group_net_out_of_range"),
    );
    vi.mocked(listSettlementCounterparties).mockResolvedValue([other]);
    vi.mocked(receiptHistory).mockResolvedValue([{ ...line, status: "paid" }]);
    renderScreen();
    expect(await screen.findByRole("alert")).toHaveTextContent("outside the supported range");
    fireEvent.click(await screen.findByText("Receipt history"));
    expect(await screen.findByText(/1.25 points · paid/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "I've reviewed this" })).toBeEnabled();
  });

  it("finds receipt history on overflow even without a pending warning", async () => {
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockRejectedValue(
      new BackendError("overflow", 409, "group_net_out_of_range"),
    );
    vi.mocked(listSettlementCounterparties).mockResolvedValue([other]);
    vi.mocked(receiptHistory).mockResolvedValue([line]);
    renderScreen();
    fireEvent.click(await screen.findByText("Receipt history"));
    expect(await screen.findByRole("button", { name: "I sent it" })).toBeEnabled();
  });

  it("keeps an uncertain action and reuses its key after remount", async () => {
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    vi.mocked(transitionGroup)
      .mockRejectedValueOnce(new TypeError("network disconnected"))
      .mockResolvedValueOnce();
    const first = renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Mark group sent" }));
    expect(await screen.findByText(/outcome is unknown/)).toBeInTheDocument();
    const key = vi.mocked(transitionGroup).mock.calls[0]?.[3];
    expect(key).toBeTruthy();
    first.unmount();
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(transitionGroup).toHaveBeenCalledTimes(2));
    expect(vi.mocked(transitionGroup).mock.calls[1]?.[3]).toBe(key);
    expect(await screen.findByText(/Action completed/)).toBeInTheDocument();
    expect(sessionStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBeNull();
  });

  it("refuses an expired retry and keeps other actions blocked", async () => {
    sessionStorage.setItem(
      `p2picks:settlement-action:${user.id}`,
      JSON.stringify({
        intent: { kind: "group", other, action: "sent", revision: "group-revision" },
        key: revision,
        createdAt: Date.now() - 23 * 60 * 60 * 1000,
      }),
    );
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("too old to retry safely");
    expect(screen.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
    expect(transitionGroup).not.toHaveBeenCalled();
  });

  it("does not send a mutation when retry storage cannot be written", async () => {
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    vi.stubGlobal("sessionStorage", {
      getItem: () => null,
      setItem: () => {
        throw new Error("storage unavailable");
      },
      removeItem: () => {},
    });
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Mark group sent" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("storage is unavailable");
    expect(transitionGroup).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
  });

  it("blocks sending when reviews cannot be checked", async () => {
    vi.mocked(listPendingReviews).mockRejectedValue(new BackendError("unavailable", 503));
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    renderScreen();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Cannot check balance-change reviews",
    );
    expect(await screen.findByRole("button", { name: "Mark group sent" })).toBeDisabled();
  });

  it("expires the session when a settlement read returns 401", async () => {
    vi.mocked(listPendingReviews).mockRejectedValue(new BackendError("expired", 401));
    renderScreen();
    await waitFor(() => expect(expire).toHaveBeenCalled());
  });

  it("preserves an uncertain action when a retry is rejected or the session expires", async () => {
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...group, pendingReviewRevision: null }]);
    vi.mocked(transitionGroup)
      .mockRejectedValueOnce(new TypeError("disconnected"))
      .mockRejectedValueOnce(new BackendError("forbidden", 403))
      .mockRejectedValueOnce(new BackendError("expired", 401));
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Mark group sent" }));
    expect(await screen.findByText(/outcome is unknown/)).toBeInTheDocument();
    const stored = sessionStorage.getItem(`p2picks:settlement-action:${user.id}`);
    fireEvent.click(screen.getByRole("button", { name: "Retry same action" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "original action may have succeeded",
    );
    expect(sessionStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBe(stored);
    fireEvent.click(screen.getByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(expire).toHaveBeenCalled());
    expect(sessionStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBe(stored);
  });

  it("refreshes stale revisions without replaying with a new key", async () => {
    vi.mocked(acknowledgeReview).mockRejectedValue(new BackendError("stale", 409, "stale_review"));
    vi.mocked(listPendingReviews)
      .mockResolvedValueOnce([review])
      .mockResolvedValue([{ ...review, pendingReviewRevision: lineId }]);
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "I've reviewed this" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("changed");
    await waitFor(() => expect(listPendingReviews).toHaveBeenCalledTimes(2));
    expect(acknowledgeReview).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBeNull();
  });

  it("checks confirmation before forgiving an individual receipt", async () => {
    const incoming = { ...line, payerId: other, payeeId: user.id };
    vi.mocked(listPendingReviews).mockResolvedValue([]);
    vi.mocked(listSettlementGroups).mockResolvedValue([
      { ...group, netCents: 125n, lines: [incoming], pendingReviewRevision: null },
    ]);
    vi.mocked(transitionLine).mockResolvedValue();
    renderScreen();
    fireEvent.click(await screen.findByText("Receipt history"));
    fireEvent.click(screen.getByRole("button", { name: "Forgive" }));
    expect(transitionLine).not.toHaveBeenCalled();
    fireEvent.click(
      within(screen.getByRole("alertdialog")).getByRole("button", { name: "Confirm" }),
    );
    await waitFor(() =>
      expect(transitionLine).toHaveBeenCalledWith(
        user.id,
        other,
        lineId,
        "forgive",
        1,
        expect.any(String),
      ),
    );
  });
});

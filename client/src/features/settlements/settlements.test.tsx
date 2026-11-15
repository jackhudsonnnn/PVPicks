import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { getProfile } from "@/features/people/api";
import { SettlementReceipts } from "./settlements";
import {
  acknowledgeReview,
  listSettlementGroups,
  receiptHistory,
  transitionGroup,
  transitionLine,
  type SettlementGroup,
  type SettlementLine,
} from "./api";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
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
/** You owe this receipt; the server says you may send it or ask to forgive it, both waiting on a review. */
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
  actions: [
    { action: "sent", blockedByReview: true },
    { action: "ask-forgive", blockedByReview: true },
  ],
};
const group: SettlementGroup = {
  otherUserId: other,
  netCents: -125n,
  revision: "group-revision",
  lines: [line],
  pendingReviewRevision: revision,
  actions: [
    { action: "sent", blockedByReview: true },
    { action: "ask", blockedByReview: true },
  ],
};
/** The same group once the warning is reviewed: nothing waits any more. */
const reviewed: SettlementGroup = {
  ...group,
  pendingReviewRevision: null,
  actions: group.actions.map((a) => ({ ...a, blockedByReview: false })),
  lines: [{ ...line, actions: line.actions.map((a) => ({ ...a, blockedByReview: false })) }],
};
const expire = vi.fn();

function renderScreen() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SettlementReceipts user={user} expire={expire} />
    </QueryClientProvider>,
  );
}

/** Opens the person's receipts, where the group and receipt actions live. */
async function openReceipts() {
  fireEvent.click(await screen.findByRole("button", { name: "Settle-up receipts" }));
  return within(await screen.findByRole("dialog"));
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(getProfile).mockResolvedValue({ id: other, username: "player", hue: 1 });
  vi.mocked(listSettlementGroups).mockResolvedValue([group]);
  vi.mocked(receiptHistory).mockResolvedValue([line]);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("settle-ups", () => {
  it("shows each person's net and what's waiting", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([
      { ...reviewed, lines: [{ ...line, status: "sent" }] },
    ]);
    renderScreen();
    expect(await screen.findByText("player")).toBeInTheDocument();
    expect(screen.getByText("You pay them")).toBeInTheDocument();
    expect(screen.getByText("1 table · 1 open")).toBeInTheDocument();
    expect(screen.getByText("Awaiting receipt")).toBeInTheDocument();
  });

  it("keeps payer actions disabled until the balance change is reviewed", async () => {
    vi.mocked(acknowledgeReview).mockResolvedValue();
    vi.mocked(listSettlementGroups).mockResolvedValueOnce([group]).mockResolvedValue([reviewed]);
    vi.mocked(receiptHistory).mockResolvedValueOnce([line]).mockResolvedValue(reviewed.lines);
    renderScreen();
    expect(await screen.findByText("Balance changed: review")).toBeInTheDocument();
    const dialog = await openReceipts();
    expect(await dialog.findByText(/Balance changed with/)).toBeInTheDocument();
    expect(dialog.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
    expect(await dialog.findByRole("button", { name: "I sent it" })).toBeDisabled();
    fireEvent.click(dialog.getByRole("button", { name: "I've reviewed this" }));
    await waitFor(() =>
      expect(dialog.getByRole("button", { name: "Mark group sent" })).toBeEnabled(),
    );
    expect(acknowledgeReview).toHaveBeenCalledWith(other, revision, expect.any(String));
    await waitFor(() => expect(dialog.getByRole("button", { name: "I sent it" })).toBeEnabled());
  });

  it("offers only the actions the server lists", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([{ ...reviewed, actions: [] }]);
    vi.mocked(receiptHistory).mockResolvedValue([{ ...line, status: "paid", actions: [] }]);
    renderScreen();
    const dialog = await openReceipts();
    expect(await dialog.findByText("Completed")).toBeInTheDocument();
    expect(dialog.getByText("−1.25")).toBeInTheDocument();
    expect(dialog.queryByRole("button", { name: "Mark group sent" })).toBeNull();
    expect(dialog.queryByRole("button", { name: "I sent it" })).toBeNull();
  });

  it("shows why receipts can't load", async () => {
    vi.mocked(listSettlementGroups).mockRejectedValue(new BackendError("unavailable", 503));
    renderScreen();
    expect(await screen.findByRole("alert")).toHaveTextContent("returned an error (503)");
  });

  it("keeps an uncertain action and reuses its key after remount", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.mocked(transitionGroup)
      .mockRejectedValueOnce(new TypeError("network disconnected"))
      .mockResolvedValueOnce();
    const first = renderScreen();
    const dialog = await openReceipts();
    fireEvent.click(await dialog.findByRole("button", { name: "Mark group sent" }));
    expect(await dialog.findByText(/may not have been saved/)).toBeInTheDocument();
    const key = vi.mocked(transitionGroup).mock.calls[0]?.[3];
    expect(key).toBeTruthy();
    first.unmount();
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(transitionGroup).toHaveBeenCalledTimes(2));
    expect(vi.mocked(transitionGroup).mock.calls[1]?.[3]).toBe(key);
    await waitFor(() =>
      expect(localStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBeNull(),
    );
  });

  it("refuses an expired retry and keeps other actions blocked", async () => {
    localStorage.setItem(
      `p2picks:settlement-action:${user.id}`,
      JSON.stringify({
        intent: { kind: "group", other, action: "sent", revision: "group-revision" },
        key: revision,
        createdAt: Date.now() - 23 * 60 * 60 * 1000,
      }),
    );
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("too old to retry safely");
    const dialog = await openReceipts();
    expect(dialog.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
    expect(transitionGroup).not.toHaveBeenCalled();
  });

  it("does not send a mutation when retry storage cannot be written", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.stubGlobal("localStorage", {
      getItem: () => null,
      setItem: () => {
        throw new Error("storage unavailable");
      },
      removeItem: () => {},
    });
    renderScreen();
    const dialog = await openReceipts();
    fireEvent.click(await dialog.findByRole("button", { name: "Mark group sent" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent("storage is unavailable");
    expect(transitionGroup).not.toHaveBeenCalled();
    expect(dialog.getByRole("button", { name: "Mark group sent" })).toBeDisabled();
  });

  it("expires the session when a settlement read returns 401", async () => {
    vi.mocked(listSettlementGroups).mockRejectedValue(new BackendError("expired", 401));
    renderScreen();
    await waitFor(() => expect(expire).toHaveBeenCalled());
  });

  it("preserves an uncertain action when a retry is rejected or the session expires", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.mocked(transitionGroup)
      .mockRejectedValueOnce(new TypeError("disconnected"))
      .mockRejectedValueOnce(new BackendError("forbidden", 403))
      .mockRejectedValueOnce(new BackendError("expired", 401));
    renderScreen();
    const dialog = await openReceipts();
    fireEvent.click(await dialog.findByRole("button", { name: "Mark group sent" }));
    expect(await dialog.findByText(/may not have been saved/)).toBeInTheDocument();
    const stored = localStorage.getItem(`p2picks:settlement-action:${user.id}`);
    fireEvent.click(dialog.getByRole("button", { name: "Retry same action" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent(
      "original action may have succeeded",
    );
    expect(localStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBe(stored);
    fireEvent.click(dialog.getByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(expire).toHaveBeenCalled());
    expect(localStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBe(stored);
  });

  it("refreshes stale revisions without replaying with a new key", async () => {
    vi.mocked(acknowledgeReview).mockRejectedValue(new BackendError("stale", 409, "stale_review"));
    vi.mocked(listSettlementGroups)
      .mockResolvedValueOnce([group])
      .mockResolvedValue([{ ...group, pendingReviewRevision: lineId }]);
    renderScreen();
    const dialog = await openReceipts();
    fireEvent.click(await dialog.findByRole("button", { name: "I've reviewed this" }));
    expect(await screen.findByText(/receipt or warning changed/)).toBeInTheDocument();
    await waitFor(() => expect(listSettlementGroups).toHaveBeenCalledTimes(2));
    expect(acknowledgeReview).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(`p2picks:settlement-action:${user.id}`)).toBeNull();
  });

  it("checks confirmation before forgiving an individual receipt", async () => {
    const incoming: SettlementLine = {
      ...line,
      payerId: other,
      payeeId: user.id,
      actions: [
        { action: "confirm", blockedByReview: false },
        { action: "forgive", blockedByReview: false },
      ],
    };
    vi.mocked(listSettlementGroups).mockResolvedValue([
      { ...reviewed, netCents: 125n, lines: [incoming] },
    ]);
    vi.mocked(receiptHistory).mockResolvedValue([incoming]);
    vi.mocked(transitionLine).mockResolvedValue();
    renderScreen();
    const dialog = await openReceipts();
    fireEvent.click(await dialog.findByRole("button", { name: "Forgive" }));
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

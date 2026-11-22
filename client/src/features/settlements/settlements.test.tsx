import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { getProfile } from "@/features/people/api";
import { SettlementReceipts } from "./settlements";
import {
  acknowledgeReview,
  createSettle,
  listSettlementGroups,
  listSettles,
  rejectSettle,
  type Settle,
  type SettlementGroup,
  type SettlementLine,
} from "./api";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("@/features/tables/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/tables/api")>()),
  listTables: vi.fn(async () => []),
  listArchives: vi.fn(async () => []),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listSettlementGroups: vi.fn(),
  listSettles: vi.fn(),
  acknowledgeReview: vi.fn(),
  createSettle: vi.fn(),
  rejectSettle: vi.fn(),
}));

const user = { id: "123e4567-e89b-42d3-a456-426614174000", username: "testuser", hue: 2 };
const other = "123e4567-e89b-42d3-a456-426614174001";
const revision = "123e4567-e89b-42d3-a456-426614174003";
const owe: SettlementLine = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  settlementId: revision,
  tableId: revision,
  payerId: user.id,
  payeeId: other,
  amountCents: 500n,
  status: "unpaid",
  revision: 0,
  settleId: null,
  createdAt: "2026-11-11T12:00:00Z",
  updatedAt: "2026-11-11T12:00:00Z",
};
const owed: SettlementLine = {
  ...owe,
  id: "123e4567-e89b-42d3-a456-426614174005",
  payerId: other,
  payeeId: user.id,
  amountCents: 375n,
};
/** You owe them 1.25 on balance, and a new settlement changed it: review first. */
const group: SettlementGroup = {
  otherUserId: other,
  netCents: -125n,
  lines: [owe, owed],
  pendingReviewRevision: revision,
  pendingSettles: [],
};
const reviewed: SettlementGroup = { ...group, pendingReviewRevision: null };
const incoming: Settle = {
  id: "123e4567-e89b-42d3-a456-426614174006",
  kind: "sent",
  senderId: other,
  otherId: user.id,
  payerId: other,
  payeeId: user.id,
  amountCents: 300n,
  createdAt: new Date(Date.now() - 2 * 3_600_000).toISOString(),
  rejectBy: new Date(Date.now() + 46 * 3_600_000).toISOString(),
  rejectedAt: null,
  canReject: true,
  lines: [],
};
const expire = vi.fn();
const storageKey = `p2picks:settlement-action:${user.id}`;

function renderScreen() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SettlementReceipts user={user} expire={expire} />
    </QueryClientProvider>,
  );
}

async function openLedger(index = 0) {
  fireEvent.click((await screen.findAllByRole("button", { name: "Point Ledger" }))[index]!);
  return within(await screen.findByRole("dialog"));
}

/** Selects every open receipt and confirms the action whose button reads `label`. */
async function settleAll(dialog: ReturnType<typeof within>, label: string) {
  fireEvent.click(await dialog.findByRole("checkbox", { name: /Select all/ }));
  fireEvent.click(dialog.getByRole("button", { name: label }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
}

beforeEach(() => {
  localStorage.clear();
  vi.resetAllMocks();
  vi.mocked(getProfile).mockResolvedValue({ id: other, username: "player", hue: 1 });
  vi.mocked(listSettlementGroups).mockResolvedValue([group]);
  vi.mocked(listSettles).mockResolvedValue({ items: [], nextCursor: null });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("settle-up cards", () => {
  it("show the person, net, tables and open receipts", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    renderScreen();
    expect(await screen.findByText("player")).toBeInTheDocument();
    expect(screen.getByText("You're down")).toBeInTheDocument();
    expect(screen.getByText("1 table · 2 open")).toBeInTheDocument();
    expect(document.querySelector("[data-tone]")?.getAttribute("data-tone")).toBe("owe");
  });

  it("are yellow for a balance review or a send you can reject, and sort yellow, red, green, neutral", async () => {
    const people = ["a", "b", "c", "d"].map((c) => `123e4567-e89b-42d3-a456-42661417400${c}`);
    vi.mocked(listSettlementGroups).mockResolvedValue([
      { ...reviewed, otherUserId: people[0]!, netCents: 0n },
      { ...reviewed, otherUserId: people[1]!, netCents: 200n },
      { ...reviewed, otherUserId: people[2]!, netCents: -200n },
      { ...reviewed, otherUserId: people[3]!, netCents: 0n, pendingSettles: [incoming] },
    ]);
    renderScreen();
    await screen.findAllByRole("button", { name: "Point Ledger" });
    expect(
      [...document.querySelectorAll("[data-tone]")].map((card) => card.getAttribute("data-tone")),
    ).toEqual(["attention", "owe", "owed", "even"]);
    // The color says it; the card carries no extra lines for it.
    expect(screen.queryByText(/reject within/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Balance changed/)).not.toBeInTheDocument();
  });

  it("shows why settle-ups can't load, and expires the session on 401", async () => {
    vi.mocked(listSettlementGroups).mockRejectedValue(new BackendError("expired", 401));
    renderScreen();
    await waitFor(() => expect(expire).toHaveBeenCalled());
  });
});

describe("the ledger", () => {
  it("says who is down, and waits for the balance review before settling", async () => {
    vi.mocked(acknowledgeReview).mockResolvedValue();
    renderScreen();
    const dialog = await openLedger();
    expect(dialog.getByRole("heading", { name: "Ledger" })).toBeInTheDocument();
    expect(dialog.getByText(/You're down/)).toHaveTextContent("You're down 1.25 pts with player");
    fireEvent.click(dialog.getByRole("checkbox", { name: /Select all/ }));
    expect(dialog.getByRole("button", { name: "Settle 1.25" })).toBeDisabled();
    fireEvent.click(dialog.getByRole("button", { name: "Got it" }));
    await waitFor(() =>
      expect(acknowledgeReview).toHaveBeenCalledWith(other, revision, expect.any(String)),
    );
  });

  it("nets a selection and offers settle or mark as settled by side", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.mocked(createSettle).mockResolvedValue({ ...incoming, senderId: user.id });
    renderScreen();
    const dialog = await openLedger();
    const [, owesRow, owedRow] = dialog.getAllByRole("checkbox");
    fireEvent.click(owedRow!);
    expect(dialog.getByText(/You're up/)).toHaveTextContent("You're up 3.75");
    expect(dialog.getByRole("button", { name: "Mark 3.75 as settled" })).toBeInTheDocument();
    expect(dialog.queryByRole("button", { name: /Forgive/ })).not.toBeInTheDocument();
    fireEvent.click(owesRow!);
    // The header says "You're down" too; the selection's line is the last.
    expect(dialog.getAllByText(/You're down/).at(-1)).toHaveTextContent(/^You're down 1\.25$/);
    fireEvent.click(dialog.getByRole("button", { name: "Settle 1.25" }));
    expect(await screen.findByText(/They have 48 hours to reject it/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
      expect(createSettle).toHaveBeenCalledWith(
        other,
        "sent",
        [owe.id, owed.id],
        expect.any(String),
      ),
    );
  });

  it("opens on History when you can reject a send, and rejects it after confirming", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([
      { ...reviewed, pendingSettles: [incoming] },
    ]);
    vi.mocked(listSettles).mockResolvedValue({
      items: [
        incoming,
        { ...incoming, id: owe.id, rejectedAt: incoming.createdAt, canReject: false },
      ],
      nextCursor: null,
    });
    vi.mocked(rejectSettle).mockResolvedValue();
    renderScreen();
    const dialog = await openLedger();
    expect(dialog.getByRole("tab", { name: "History", selected: true })).toBeInTheDocument();
    expect(await dialog.findAllByText("player settled 3.00")).toHaveLength(2);
    expect(dialog.getByText(/Can be rejected for 46h/)).toBeInTheDocument();
    expect(dialog.getByText(/Rejected by you/)).toBeInTheDocument();
    fireEvent.click(dialog.getByRole("button", { name: "Reject" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(rejectSettle).toHaveBeenCalledWith(incoming.id, expect.any(String)));
  });
});

describe("settling safely", () => {
  it("keeps an uncertain settle and reuses its key after remount", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.mocked(createSettle)
      .mockRejectedValueOnce(new TypeError("network disconnected"))
      .mockResolvedValueOnce(incoming);
    const first = renderScreen();
    const dialog = await openLedger();
    await settleAll(dialog, "Settle 1.25");
    expect(await dialog.findByText(/may not have been saved/)).toBeInTheDocument();
    const key = vi.mocked(createSettle).mock.calls[0]?.[3];
    expect(key).toBeTruthy();
    first.unmount();
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(createSettle).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createSettle).mock.calls[1]?.[3]).toBe(key);
    await waitFor(() => expect(localStorage.getItem(storageKey)).toBeNull());
  });

  it("refuses an expired retry and keeps other settles blocked", async () => {
    localStorage.setItem(
      storageKey,
      JSON.stringify({
        intent: { kind: "settle", other, settle: "sent", lineIds: [owe.id] },
        key: revision,
        createdAt: Date.now() - 23 * 60 * 60 * 1000,
      }),
    );
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: "Retry same action" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("too old to retry safely");
    const dialog = await openLedger();
    fireEvent.click(dialog.getByRole("checkbox", { name: /Select all/ }));
    expect(dialog.getByRole("button", { name: "Settle 1.25" })).toBeDisabled();
    expect(createSettle).not.toHaveBeenCalled();
  });

  it("does not send when retry storage cannot be written", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.stubGlobal("localStorage", {
      getItem: () => null,
      setItem: () => {
        throw new Error("storage unavailable");
      },
      removeItem: () => {},
    });
    renderScreen();
    const dialog = await openLedger();
    await settleAll(dialog, "Settle 1.25");
    expect(await dialog.findByRole("alert")).toHaveTextContent("storage is unavailable");
    expect(createSettle).not.toHaveBeenCalled();
  });

  it("preserves an uncertain settle when a retry is rejected or the session expires", async () => {
    vi.mocked(listSettlementGroups).mockResolvedValue([reviewed]);
    vi.mocked(createSettle)
      .mockRejectedValueOnce(new TypeError("disconnected"))
      .mockRejectedValueOnce(new BackendError("forbidden", 403))
      .mockRejectedValueOnce(new BackendError("expired", 401));
    renderScreen();
    const dialog = await openLedger();
    await settleAll(dialog, "Settle 1.25");
    expect(await dialog.findByText(/may not have been saved/)).toBeInTheDocument();
    const stored = localStorage.getItem(storageKey);
    fireEvent.click(dialog.getByRole("button", { name: "Retry same action" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent(
      "original action may have succeeded",
    );
    expect(localStorage.getItem(storageKey)).toBe(stored);
    fireEvent.click(dialog.getByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(expire).toHaveBeenCalled());
    expect(localStorage.getItem(storageKey)).toBe(stored);
  });

  it("refreshes after a stale review without replaying with a new key", async () => {
    vi.mocked(acknowledgeReview).mockRejectedValue(new BackendError("stale", 409, "stale_review"));
    vi.mocked(listSettlementGroups)
      .mockResolvedValueOnce([group])
      .mockResolvedValue([{ ...group, pendingReviewRevision: owe.id }]);
    renderScreen();
    const dialog = await openLedger();
    fireEvent.click(dialog.getByRole("button", { name: "Got it" }));
    expect(await screen.findByText(/Something changed/)).toBeInTheDocument();
    await waitFor(() => expect(listSettlementGroups).toHaveBeenCalledTimes(2));
    expect(acknowledgeReview).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(storageKey)).toBeNull();
  });
});

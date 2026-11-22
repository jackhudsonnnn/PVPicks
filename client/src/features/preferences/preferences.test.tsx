import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { PreferencesPage } from "./preferences";
import { parseCap } from "./rules";
import {
  getDebt,
  getDebtCap,
  getInvitePreferences,
  putDebtCap,
  putInvitePreferences,
  updateProfile,
  deleteAccount,
} from "./api";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getInvitePreferences: vi.fn(),
  putInvitePreferences: vi.fn(),
  getDebtCap: vi.fn(),
  putDebtCap: vi.fn(),
  getDebt: vi.fn(),
  updateProfile: vi.fn(),
  deleteAccount: vi.fn(),
}));
const onAccountDeleted = vi.fn();

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me_one", hue: 2 };

function renderPage() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <PreferencesPage user={me} expire={() => {}} onAccountDeleted={onAccountDeleted} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getInvitePreferences).mockResolvedValue({ friends: "open", following: "request" });
  vi.mocked(getDebtCap).mockResolvedValue(null);
  vi.mocked(getDebt).mockResolvedValue(0);
  vi.mocked(putInvitePreferences).mockResolvedValue();
  vi.mocked(putDebtCap).mockResolvedValue();
  vi.mocked(updateProfile).mockResolvedValue({ ...me, username: "new_name" });
});

describe("points limit input", () => {
  it("accepts whole points only", () => {
    expect(parseCap(" 25 ")).toEqual({ cents: 2500 });
    expect(parseCap("2.5")).toHaveProperty("problem");
    expect(parseCap("0")).toHaveProperty("problem");
    expect(parseCap("21474837")).toHaveProperty("problem");
  });
});

describe("preferences page", () => {
  it("changes who can add you, keeping the other setting", async () => {
    renderPage();
    // The first "Closed" belongs to the Friends row.
    fireEvent.click((await screen.findAllByRole("tab", { name: "Closed" }))[0]!);
    await waitFor(() =>
      expect(putInvitePreferences).toHaveBeenCalledWith(
        { friends: "closed", following: "request" },
        expect.any(String),
      ),
    );
    expect(
      await screen.findByText(/Pending invitations that no longer qualify/),
    ).toBeInTheDocument();
  });

  it("validates and saves a new username and color", async () => {
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    const field = await screen.findByLabelText("Username");
    fireEvent.change(field, { target: { value: "no spaces!" } });
    fireEvent.click(screen.getByRole("button", { name: "Save username" }));
    expect(screen.getByRole("alert")).toHaveTextContent("letters, numbers and underscores");
    expect(updateProfile).not.toHaveBeenCalled();
    fireEvent.change(field, { target: { value: " New_Name " } });
    fireEvent.click(screen.getByRole("button", { name: "Save username" }));
    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith({ username: "new_name" }, expect.any(String)),
    );
    fireEvent.click(await screen.findByRole("radio", { name: "Color 4" }));
    await waitFor(() =>
      expect(updateProfile).toHaveBeenLastCalledWith({ hue: 4 }, expect.any(String)),
    );
  });

  it("reports a taken username", async () => {
    vi.mocked(updateProfile).mockRejectedValue(new BackendError("x", 409, "username_taken"));
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    fireEvent.change(await screen.findByLabelText("Username"), { target: { value: "pro" } });
    fireEvent.click(screen.getByRole("button", { name: "Save username" }));
    expect(await screen.findByText("Someone already has that username.")).toBeInTheDocument();
  });

  it("sets a points limit in whole points", async () => {
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    fireEvent.change(await screen.findByLabelText("Points limit"), {
      target: { value: "50" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Set limit" }));
    await waitFor(() => expect(putDebtCap).toHaveBeenCalledWith(5000, expect.any(String)));
  });

  it("locks the limit while you're down", async () => {
    vi.mocked(getDebt).mockResolvedValue(125);
    vi.mocked(getDebtCap).mockResolvedValue(5000);
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    expect(await screen.findByText("1.25 points")).toBeInTheDocument();
    expect(screen.getByText(/Settle what you're down/)).toBeInTheDocument();
    expect(screen.getByLabelText("Points limit")).toBeDisabled();
    expect(screen.getByRole("switch")).toBeDisabled();
  });

  it("turns the limit off when you're not down", async () => {
    vi.mocked(getDebtCap).mockResolvedValue(5000);
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    fireEvent.click(await screen.findByRole("switch"));
    await waitFor(() => expect(putDebtCap).toHaveBeenCalledWith(null, expect.any(String)));
  });

  it("deletes the account once you're square, after typing your username", async () => {
    vi.mocked(deleteAccount)
      .mockRejectedValueOnce(
        new BackendError("x", 409, "account_not_square", "Settle up", {
          blockers: ["open_receipts"],
        }),
      )
      .mockResolvedValueOnce();
    renderPage();
    fireEvent.click(screen.getByRole("tab", { name: "Account" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete my account" }));
    const confirm = await screen.findByLabelText(/to confirm/);
    const remove = screen.getByRole("button", { name: "Delete forever" });
    fireEvent.change(confirm, { target: { value: "someone else" } });
    expect(remove).toBeDisabled();
    fireEvent.change(confirm, { target: { value: me.username } });
    fireEvent.click(remove);
    expect(await screen.findByRole("alert")).toHaveTextContent("receipts that aren't settled");
    expect(onAccountDeleted).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Delete forever" }));
    await waitFor(() => expect(onAccountDeleted).toHaveBeenCalledOnce());
    expect(deleteAccount).toHaveBeenLastCalledWith(me.username);
  });
});

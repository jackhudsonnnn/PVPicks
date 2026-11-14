import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "@/app/app";
import { useIsolatedBackend } from "@/test/isolated-backend";

vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => () => Promise.resolve(),
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: { pathname: "/friends" } }),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe.skipIf(!process.env["PVPICKS_TEST_PORT"])("isolated settlement integration", () => {
  it("signs in, reviews a changed balance and sends the group", async () => {
    useIsolatedBackend();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <App />
      </QueryClientProvider>,
    );
    fireEvent.change(await screen.findByLabelText("Username"), {
      target: { value: "previewpayer" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "a secure test password" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Friends" })).toBeInTheDocument();
    expect(await screen.findByText("Balance changed: review")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Settle-up receipts" }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(await dialog.findByText(/Balance changed with/)).toBeInTheDocument();
    expect(await dialog.findByRole("button", { name: "Mark group sent" })).toBeDisabled();
    fireEvent.click(dialog.getByRole("button", { name: "I've reviewed this" }));
    await waitFor(() =>
      expect(dialog.getByRole("button", { name: "Mark group sent" })).toBeEnabled(),
    );
    fireEvent.click(dialog.getByRole("button", { name: "Mark group sent" }));
    await waitFor(() =>
      expect(dialog.queryByRole("button", { name: "Mark group sent" })).toBeNull(),
    );
    // The payment now waits for the payee to confirm it.
    expect(await screen.findByText("Awaiting receipt")).toBeInTheDocument();
  }, 30000);
});

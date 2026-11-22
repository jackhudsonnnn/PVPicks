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
  it("signs in, reviews a changed balance and sends every open receipt", async () => {
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
    fireEvent.click(await screen.findByRole("button", { name: "Ledger" }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(await dialog.findByText(/Your balance with .* changed/)).toBeInTheDocument();
    fireEvent.click(await dialog.findByRole("checkbox", { name: /Select all/ }));
    const send = dialog.getByRole("button", { name: /^Send / });
    expect(send).toBeDisabled();
    fireEvent.click(dialog.getByRole("button", { name: "Got it" }));
    await waitFor(() => expect(dialog.getByRole("button", { name: /^Send / })).toBeEnabled());
    fireEvent.click(dialog.getByRole("button", { name: /^Send / }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    // Settled at once: nothing open is left, and History has the send the payee can still reject.
    expect(await dialog.findByText(/Nothing open with/)).toBeInTheDocument();
    fireEvent.click(dialog.getByRole("tab", { name: "History" }));
    expect(await dialog.findByText(/You sent/)).toBeInTheDocument();
  }, 30000);
});

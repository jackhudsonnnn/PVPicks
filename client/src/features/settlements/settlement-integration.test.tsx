import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BackendApp } from "./backend-app";
import { useIsolatedBackend } from "./backend-integration-utils";

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
        <BackendApp />
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
    expect(await screen.findByText(/Balance changed with/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Mark group sent" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "I've reviewed this" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Mark group sent" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "Mark group sent" }));
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Mark group sent" })).toBeNull(),
    );
    fireEvent.click(screen.getByText("Receipt history"));
    expect(await screen.findByText(/1.00 points · sent/)).toBeInTheDocument();
  }, 30000);
});

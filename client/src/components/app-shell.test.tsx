import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { AppShell } from "./app-shell";

const route = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => string }) =>
    select({ location: route }),
}));
vi.mock("@/features/auth/auth-provider", () => ({ useAuth: () => ({ signOut: vi.fn() }) }));
vi.mock("@/features/store", () => ({
  useMe: () => "me",
  useAppStore: () => ({ state: { tables: [], bets: [], messages: [] } }),
  myInvites: () => [],
  myTables: () => [],
}));
vi.mock("@/features/bets/components/live-ticker", () => ({
  LiveTicker: () => <section aria-label="Live games" />,
}));

describe("table game ticker", () => {
  it("hides the ticker on table pages and restores it on other pages", () => {
    route.pathname = "/tables/example";
    const { rerender } = render(<AppShell>Table content</AppShell>);
    expect(screen.queryByRole("region", { name: "Live games" })).not.toBeInTheDocument();
    expect(screen.getByText("Table content")).toBeInTheDocument();

    route.pathname = "/friends";
    rerender(<AppShell>Friends content</AppShell>);
    expect(screen.getByRole("region", { name: "Live games" })).toBeInTheDocument();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { getAppMode } from "@/lib/app-mode-api";
import { demoAccounts } from "./api";
import { AdminSignIn } from "./admin-sign-in";
import { SignIn } from "./sign-in";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  demoAccounts: vi.fn(),
}));
vi.mock("@/lib/app-mode-api", () => ({ getAppMode: vi.fn() }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const account = (username: string, hue: number, role: "PLAYER" | "ADMIN" = "PLAYER") => ({
  id: `123e4567-e89b-42d3-a456-42661417400${hue}`,
  username,
  hue,
  role,
  frozen: false,
});

const login = vi.fn();
const loginAs = vi.fn();
const loginWithCode = vi.fn();

function renderSignIn(
  page: ReactNode = <SignIn loginAs={loginAs} loginWithCode={loginWithCode} notice={null} />,
) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      {page}
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  login.mockResolvedValue(undefined);
  loginAs.mockResolvedValue(undefined);
  loginWithCode.mockResolvedValue(undefined);
  vi.mocked(getAppMode).mockResolvedValue("dev");
});

describe("sign-in", () => {
  it("offers the demo's accounts without passwords and searches demo users", async () => {
    vi.mocked(demoAccounts).mockImplementation(async (query) =>
      query === ""
        ? { main: [account("newbie", 1), account("pro", 3)], others: [account("finn_swish", 2)] }
        : { main: [], others: [account("hoopsdana23", 4)] },
    );
    renderSignIn();
    expect(await screen.findByText("Pick an account")).toBeInTheDocument();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    expect(screen.getByText(/60 friends/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^pro/ }));
    await waitFor(() => expect(loginAs).toHaveBeenCalledWith(account("pro", 3).id));
    fireEvent.change(screen.getByLabelText("Demo users"), { target: { value: "hoops" } });
    expect(await screen.findByText("hoopsdana23")).toBeInTheDocument();
    expect(demoAccounts).toHaveBeenCalledWith("hoops", expect.anything());
  });

  it("asks LAN players for their username and code, with no way to the admin form", async () => {
    vi.mocked(getAppMode).mockResolvedValue("lan");
    renderSignIn();
    const code = await screen.findByLabelText("Your code");
    expect(demoAccounts).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Admin" })).not.toBeInTheDocument();
    fireEvent.change(code, { target: { value: "k7-qm" } });
    // Both are needed: the code alone doesn't say whose it is.
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "dana" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(loginWithCode).toHaveBeenCalledWith("dana", "K7-QM"));
  });

  it("signs the admin in with a password at /admin", async () => {
    renderSignIn(<AdminSignIn login={login} notice={null} />);
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "boss" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a long password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(login).toHaveBeenCalledWith("boss", "a long password"));
    expect(screen.getByRole("link", { name: "I have a player code" })).toHaveAttribute("href", "/");
  });

  it("keeps checking for the mode while the backend is unreachable", async () => {
    vi.mocked(getAppMode)
      .mockRejectedValueOnce(new Error("Failed to fetch"))
      .mockResolvedValue("dev");
    vi.mocked(demoAccounts).mockResolvedValue({ main: [account("pro", 3)], others: [] });
    renderSignIn();
    expect(screen.getByLabelText("Checking sign-in options")).toBeInTheDocument();
    expect(await screen.findByText("Pick an account", {}, { timeout: 5000 })).toBeInTheDocument();
  });
});

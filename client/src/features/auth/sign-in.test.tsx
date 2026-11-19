import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { demoAccounts } from "./api";
import { SignIn } from "./sign-in";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  demoAccounts: vi.fn(),
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

function renderSignIn() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SignIn login={login} loginAs={loginAs} loginWithCode={loginWithCode} notice={null} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  login.mockResolvedValue(undefined);
  loginAs.mockResolvedValue(undefined);
  loginWithCode.mockResolvedValue(undefined);
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

  it("asks players for their code without a demo, with a small way in for the admin", async () => {
    vi.mocked(demoAccounts).mockResolvedValue(null);
    renderSignIn();
    const code = await screen.findByLabelText("Your code");
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
    fireEvent.change(code, { target: { value: "k7-qm" } });
    // Both are needed: the code alone doesn't say whose it is.
    expect(screen.getByRole("button", { name: "Sign in" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "dana" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(loginWithCode).toHaveBeenCalledWith("dana", "K7-QM"));

    fireEvent.click(screen.getByRole("button", { name: "Admin" }));
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "boss" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a long password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(login).toHaveBeenCalledWith("boss", "a long password"));
  });

  it("keeps checking for the demo while the backend is unreachable", async () => {
    vi.mocked(demoAccounts)
      .mockRejectedValueOnce(new Error("Failed to fetch"))
      .mockResolvedValue({ main: [account("pro", 3)], others: [] });
    renderSignIn();
    expect(await screen.findByLabelText("Your code")).toBeInTheDocument();
    expect(await screen.findByText("Pick an account", {}, { timeout: 5000 })).toBeInTheDocument();
  });
});

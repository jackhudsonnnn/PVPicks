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

function renderSignIn() {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <SignIn login={login} loginAs={loginAs} notice={null} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  login.mockResolvedValue(undefined);
  loginAs.mockResolvedValue(undefined);
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

  it("shows the password form when the backend has no demo", async () => {
    vi.mocked(demoAccounts).mockResolvedValue(null);
    renderSignIn();
    expect(await screen.findByLabelText("Password")).toBeInTheDocument();
  });
});

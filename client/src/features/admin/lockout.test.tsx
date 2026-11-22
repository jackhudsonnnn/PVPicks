import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearSignInLockout, getSignInLockouts, type SignInLockouts } from "./dashboard-api";
import { LockoutBanner, LockoutRow } from "./lockout";

vi.mock("./dashboard-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./dashboard-api")>()),
  getSignInLockouts: vi.fn(),
  clearSignInLockout: vi.fn(),
}));

const admin = { id: "123e4567-e89b-42d3-a456-426614174000", username: "admin", hue: 4 };
const dana = "123e4567-e89b-42d3-a456-426614174001";
const soon = () => new Date(Date.now() + 9 * 60_000).toISOString();
const quiet: SignInLockouts = {
  playerLimit: 10,
  addressLimit: 100,
  address: { wrongCodes: 0, locked: false, resetsAt: null },
  players: [],
};

function wrap(ui: React.ReactNode) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      {ui}
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.resetAllMocks());

describe("sign-in lockouts", () => {
  it("names a locked-out player on Users and unlocks just them", async () => {
    vi.mocked(getSignInLockouts)
      .mockResolvedValueOnce({
        ...quiet,
        address: { wrongCodes: 12, locked: false, resetsAt: soon() },
        players: [
          { id: dana, username: "dana", hue: 2, wrongCodes: 10, locked: true, resetsAt: soon() },
        ],
      })
      .mockResolvedValue(quiet);
    vi.mocked(clearSignInLockout).mockResolvedValue();
    wrap(<LockoutBanner user={admin} />);
    expect(await screen.findByText("Locked out of code sign-in.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("dana");
    expect(screen.getByRole("alert")).toHaveTextContent("9 min left");
    fireEvent.click(screen.getByRole("button", { name: "Unlock dana" }));
    await waitFor(() => expect(clearSignInLockout).toHaveBeenCalledWith(dana));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("stays out of the way when nobody is locked", async () => {
    vi.mocked(getSignInLockouts).mockResolvedValue({
      ...quiet,
      players: [
        { id: dana, username: "dana", hue: 2, wrongCodes: 2, locked: false, resetsAt: soon() },
      ],
    });
    wrap(<LockoutBanner user={admin} />);
    await waitFor(() => expect(getSignInLockouts).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("summarizes on Perf, with Clear all off when there's nothing to clear", async () => {
    vi.mocked(getSignInLockouts).mockResolvedValue(quiet);
    wrap(<LockoutRow user={admin} />);
    expect(
      await screen.findByText(/0 locked · 0 with wrong codes · network 0 of 100/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Clear all/ })).toBeDisabled();
  });
});

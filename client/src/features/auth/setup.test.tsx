import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { redeemSetupCode } from "./api";
import { passwordProblem } from "./password-rules";
import { SetupPassword } from "./setup";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  redeemSetupCode: vi.fn(),
}));

const user = {
  id: "123e4567-e89b-42d3-a456-426614174001",
  username: "newbie",
  hue: 1,
  role: "PLAYER" as const,
};
const onDone = vi.fn();

function fill(password: string, confirm = password) {
  fireEvent.change(screen.getByLabelText("New password"), { target: { value: password } });
  fireEvent.change(screen.getByLabelText("Type it again"), { target: { value: confirm } });
  fireEvent.click(screen.getByRole("button", { name: /Set password/ }));
}

beforeEach(() => {
  vi.resetAllMocks();
  onDone.mockResolvedValue(undefined);
  window.history.replaceState(null, "", "/setup#AbCdEfGhIjKlMnOpQrStUv");
});

afterEach(() => window.history.replaceState(null, "", "/"));

describe("setting a password from a setup link", () => {
  it("reads the code from the fragment, checks the rules first, then signs in", async () => {
    vi.mocked(redeemSetupCode).mockResolvedValue(user);
    render(<SetupPassword onDone={onDone} />);
    fill("short");
    expect(await screen.findByText("Use at least 12 characters.")).toBeInTheDocument();
    expect(redeemSetupCode).not.toHaveBeenCalled();
    fill("a long passphrase", "a long passphrasx");
    expect(screen.getByText("The passwords don't match.")).toBeInTheDocument();
    expect(redeemSetupCode).not.toHaveBeenCalled();
    fill("a long passphrase");
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(user));
    expect(redeemSetupCode).toHaveBeenCalledWith("AbCdEfGhIjKlMnOpQrStUv", "a long passphrase");
    expect(window.location.hash).toBe("");
  });

  it("explains a used or expired link and the server's password rules", async () => {
    render(<SetupPassword onDone={onDone} />);
    vi.mocked(redeemSetupCode).mockRejectedValueOnce(
      new BackendError("Backend request failed.", 400, "invalid_setup_code"),
    );
    fill("a long passphrase");
    expect(await screen.findByRole("alert")).toHaveTextContent(/expired or was already used/);
    vi.mocked(redeemSetupCode).mockRejectedValueOnce(
      new BackendError(
        "Backend request failed.",
        400,
        "weak_password",
        "That password is too common; choose another.",
      ),
    );
    fill("passwordpassword");
    expect(await screen.findByRole("alert")).toHaveTextContent("That password is too common");
    expect(onDone).not.toHaveBeenCalled();
  });

  it("says when the link has no code", () => {
    window.history.replaceState(null, "", "/setup");
    render(<SetupPassword onDone={onDone} />);
    expect(screen.getByRole("alert")).toHaveTextContent(/missing its code/);
  });

  it("mirrors the server's password rules", () => {
    expect(passwordProblem("eleven char")).toMatch(/at least 12/);
    expect(passwordProblem("x".repeat(65))).toMatch(/at most 64/);
    expect(passwordProblem("é".repeat(40))).toMatch(/too long/);
    expect(passwordProblem("i am newbie for real", "Newbie")).toMatch(/username/);
    expect(passwordProblem("a perfectly fine one")).toBeNull();
  });
});

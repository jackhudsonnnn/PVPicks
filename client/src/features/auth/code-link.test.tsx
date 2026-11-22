import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BackendError } from "@/lib/api-client";
import { signInWithCode } from "./api";
import { CodeLinkSignIn } from "./code-link";

vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  signInWithCode: vi.fn(),
}));

const user = {
  id: "123e4567-e89b-42d3-a456-426614174000",
  username: "dana",
  hue: 2,
  role: "PLAYER" as const,
};

beforeEach(() => {
  vi.resetAllMocks();
  window.history.replaceState(null, "", `/signin#${user.id}.K7QM`);
});

describe("QR sign-in link", () => {
  it("signs in with the code from the link and clears it from the address bar", async () => {
    vi.mocked(signInWithCode).mockResolvedValue(user);
    const onDone = vi.fn().mockResolvedValue(undefined);
    render(<CodeLinkSignIn onDone={onDone} />);
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(user));
    expect(signInWithCode).toHaveBeenCalledWith({ player: user.id }, "K7QM");
    expect(window.location.hash).toBe("");
  });

  it("explains a code that no longer works", async () => {
    vi.mocked(signInWithCode).mockRejectedValue(new BackendError("x", 401));
    render(<CodeLinkSignIn onDone={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("no longer works");
  });

  it("refuses an old code-only link without calling the server", async () => {
    window.history.replaceState(null, "", "/signin#K7QM");
    render(<CodeLinkSignIn onDone={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("missing its code");
    expect(signInWithCode).not.toHaveBeenCalled();
  });
});

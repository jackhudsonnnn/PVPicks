import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Bet } from "./api";
import { RunItBack } from "./components/card-ticket-shell";
import { rematchToFollow } from "./ticket-model";

describe("Run it back", () => {
  const soon = new Date(Date.now() + 20_000).toISOString();

  it("starts a rematch from a finished game", () => {
    const onRun = vi.fn();
    render(<RunItBack rematch={undefined} canBet disabled={false} onRun={onRun} />);
    fireEvent.click(screen.getByRole("button", { name: "Run it back" }));
    expect(onRun).toHaveBeenCalled();
  });

  it("joins a running rematch, and says when you're in", () => {
    const onRun = vi.fn();
    const { rerender } = render(
      <RunItBack
        rematch={{ status: "ACTIVE", locksAt: soon, joined: false }}
        canBet
        disabled={false}
        onRun={onRun}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /Join the rematch · 0:(19|20)/ }));
    expect(onRun).toHaveBeenCalled();
    rerender(
      <RunItBack
        rematch={{ status: "ACTIVE", locksAt: soon, joined: true }}
        canBet
        disabled={false}
        onRun={onRun}
      />,
    );
    expect(screen.getByRole("button", { name: /You're in the rematch/ })).toBeDisabled();
  });

  it("is gone once the rematch is dealt, or if you can't bet here", () => {
    const { container, rerender } = render(
      <RunItBack
        rematch={{ status: "PENDING", locksAt: soon, joined: true }}
        canBet
        disabled={false}
        onRun={() => {}}
      />,
    );
    expect(container).toBeEmptyDOMElement();
    rerender(<RunItBack rematch={undefined} canBet={false} disabled={false} onRun={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe("following a rematch", () => {
  const id = "123e4567-e89b-42d3-a456-426614174077";
  const finished = (rematch?: Bet["rematch"]) => ({ rematch }) as Bet;
  const locksAt = new Date(Date.now() + 20_000).toISOString();

  it("moves the open ticket to a rematch you're in while it's open to join", () => {
    expect(rematchToFollow(finished({ id, status: "ACTIVE", locksAt, joined: true }))).toBe(id);
  });

  it("stays on the old game otherwise", () => {
    expect(rematchToFollow(undefined)).toBeNull();
    expect(rematchToFollow(finished())).toBeNull();
    expect(rematchToFollow(finished({ id, status: "ACTIVE", locksAt, joined: false }))).toBeNull();
    // Reopening the old game after the rematch dealt shows its results.
    expect(rematchToFollow(finished({ id, status: "PENDING", locksAt, joined: true }))).toBeNull();
  });
});

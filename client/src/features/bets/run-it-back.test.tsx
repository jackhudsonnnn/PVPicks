import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RunItBack } from "./components/card-ticket-shell";

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

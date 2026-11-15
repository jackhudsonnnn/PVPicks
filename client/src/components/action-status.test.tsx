import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ActionStatus } from "./action-status";

const base = {
  message: null,
  storageError: null,
  pending: { intent: {}, key: "k", createdAt: 0 },
  retry: () => {},
  clearAfterReview: () => {},
};

describe("ActionStatus", () => {
  it("stays hidden while the action is still being sent, so the page doesn't jump", () => {
    render(
      <ActionStatus
        actions={{ ...base, busy: true }}
        pendingText="A message may not have been sent."
        checkText=""
      />,
    );
    expect(screen.queryByText(/may not have been sent/)).not.toBeInTheDocument();
  });

  it("shows retry and clear once the outcome is unknown", () => {
    render(
      <ActionStatus
        actions={{ ...base, busy: false }}
        pendingText="A message may not have been sent."
        checkText=""
      />,
    );
    expect(screen.getByText(/may not have been sent/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry same action" })).toBeInTheDocument();
  });
});

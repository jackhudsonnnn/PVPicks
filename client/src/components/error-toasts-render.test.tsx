import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { emitError } from "@/lib/error-bus";
import { ErrorToasts } from "./error-toasts";

describe("error toast rendering", () => {
  it("renders a command failure in the toast region", async () => {
    render(<ErrorToasts />);
    act(() => emitError("Debt cap reached", "Review your debt."));
    // The test setup also mounts a toaster, so each toast can appear twice.
    expect((await screen.findAllByText("Debt cap reached")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Review your debt.").length).toBeGreaterThan(0);
  });
});

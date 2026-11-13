import { act, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { emitError } from "@/lib/error-bus";
import { ErrorToasts } from "./error-toasts";

describe("error toast rendering", () => {
  it("renders a command failure in the toast region", async () => {
    render(<ErrorToasts />);
    act(() => emitError("Debt cap reached", "Review your debt."));
    expect(await screen.findByText("Debt cap reached")).toBeTruthy();
    expect(screen.getByText("Review your debt.")).toBeTruthy();
  });
});

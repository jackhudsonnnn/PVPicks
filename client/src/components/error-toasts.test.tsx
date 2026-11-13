import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { emitError } from "@/lib/error-bus";
import { ErrorToasts } from "./error-toasts";

const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: { error: toastError }, Toaster: () => null }));

describe("ErrorToasts", () => {
  afterEach(() => toastError.mockClear());

  it("displays command errors and unsubscribes on unmount", () => {
    const { unmount } = render(<ErrorToasts />);
    emitError("Debt cap reached", "Review your debt.");
    expect(toastError).toHaveBeenCalledWith("Debt cap reached", {
      description: "Review your debt.",
    });
    unmount();
    emitError("After unmount");
    expect(toastError).toHaveBeenCalledTimes(1);
  });

  it("displays unexpected browser errors", () => {
    const { unmount } = render(<ErrorToasts />);
    window.dispatchEvent(new Event("unhandledrejection"));
    expect(toastError).toHaveBeenCalledWith("Something went wrong", {
      description: "Try that again in a moment.",
    });
    unmount();
  });

  it("keeps admin page errors available to diagnostics without duplicate events", () => {
    const { unmount } = render(<ErrorToasts admin />);
    window.dispatchEvent(new ErrorEvent("error", { message: "Diagnostic detail" }));
    expect(toastError).toHaveBeenCalledOnce();
    expect(toastError).toHaveBeenCalledWith("Page error", { description: "Diagnostic detail" });
    unmount();
  });
});

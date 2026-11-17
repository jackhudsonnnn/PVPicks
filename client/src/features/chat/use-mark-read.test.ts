import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { markRead } from "./api";
import { READ_INTERVAL_MS, useMarkRead } from "./use-mark-read";

vi.mock("./api", () => ({ markRead: vi.fn() }));

const table = "123e4567-e89b-42d3-a456-426614174000";

beforeEach(() => {
  vi.useFakeTimers();
  vi.mocked(markRead).mockResolvedValue(undefined);
});
afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});

describe("useMarkRead", () => {
  it("marks the first message at once, then at most every interval, ending on the newest", () => {
    const { rerender } = renderHook(({ id }) => useMarkRead(table, id, vi.fn()), {
      initialProps: { id: "m1" },
    });
    expect(markRead).toHaveBeenCalledTimes(1);
    expect(markRead).toHaveBeenLastCalledWith(table, "m1", expect.any(String));

    for (const id of ["m2", "m3", "m4"]) rerender({ id });
    expect(markRead).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(READ_INTERVAL_MS);
    expect(markRead).toHaveBeenCalledTimes(2);
    expect(markRead).toHaveBeenLastCalledWith(table, "m4", expect.any(String));

    rerender({ id: "m4" });
    vi.advanceTimersByTime(READ_INTERVAL_MS * 2);
    expect(markRead).toHaveBeenCalledTimes(2);
  });
});

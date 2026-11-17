import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CLIENT_PAGE, useClientPages } from "./use-client-pages";

describe("useClientPages", () => {
  it("shows a page at a time and never shrinks when items are answered", () => {
    const items = Array.from({ length: 45 }, (_, i) => i);
    const { result, rerender } = renderHook(({ list }) => useClientPages(list), {
      initialProps: { list: items },
    });
    expect(result.current.shown).toHaveLength(CLIENT_PAGE);
    expect(result.current.hasMore).toBe(true);
    act(() => result.current.more());
    expect(result.current.shown).toHaveLength(2 * CLIENT_PAGE);
    act(() => result.current.more());
    expect(result.current.shown).toHaveLength(45);
    expect(result.current.hasMore).toBe(false);
    rerender({ list: items.slice(1) });
    expect(result.current.shown).toHaveLength(44);
  });
});

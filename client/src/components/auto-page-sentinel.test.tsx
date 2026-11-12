import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AutoPageSentinel } from "./auto-page-sentinel";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("automatic paging", () => {
  it("loads once per page inside its own scroll area and re-arms for the next page", () => {
    let notify: IntersectionObserverCallback | undefined;
    const options: IntersectionObserverInit[] = [];
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: IntersectionObserverCallback, config: IntersectionObserverInit) {
          notify = callback;
          options.push(config);
        }
        observe() {}
        disconnect() {}
      },
    );
    const load = vi.fn();
    const view = (pageKey: number, hasMore = true) => (
      <div data-scroll-direction="vertical">
        <AutoPageSentinel pageKey={pageKey} hasMore={hasMore} onLoadMore={load} />
      </div>
    );
    const { container, rerender } = render(view(10));
    expect(options[0]?.root).toBe(container.firstChild);
    const intersect = () =>
      notify?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    act(() => {
      intersect();
      intersect();
    });
    expect(load).toHaveBeenCalledTimes(1);
    rerender(view(20));
    act(intersect);
    expect(load).toHaveBeenCalledTimes(2);
    rerender(view(30, false));
    expect(container.querySelector('[role="status"]')).toBeNull();
  });
});

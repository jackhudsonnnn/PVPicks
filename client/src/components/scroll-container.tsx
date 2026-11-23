import * as React from "react";
import { motion, type HTMLMotionProps } from "motion/react";
import { cva } from "class-variance-authority";
import { cn } from "@/lib/utils";

/**
 * Shared scrollable region. Use it for any list that can grow with more data
 * instead of hand-writing `overflow-*` classes.
 *
 * - `isVisible`    (default true)  — show the bordered, tinted box. Pass false for a bare scroll area.
 * - `isHorizontal` (default false) — side-scroll; "mobile" keeps vertical scrolling on desktop.
 * - `hasScrollbar` (default false) — show a thin scrollbar; hidden otherwise (still scrolls).
 *
 * - `keepPosition` (default true) — items added or removed elsewhere never move what you're looking at; see
 *                                    `useScrollAnchor`. Turn off where the list manages its own scroll (chat).
 *
 * Size, gap and layout come from `className` (e.g. `max-h-80 grid gap-2`).
 * Wrap rows in `AnimatedList`/`AnimatedItem` (`@/components/animated-list`) so adds and
 * removes slide and fade; the container is a motion `layoutScroll` node and `relative`,
 * so those animations stay correct while the list is scrolled.
 */
// Vertical lists let scrolling continue to the page once they hit their top/bottom
// (overscroll-auto); horizontal rows contain it so sideways swipes don't trigger browser back.
const scrollContainerVariants = cva("relative min-h-0 min-w-0", {
  variants: {
    isHorizontal: {
      // A lone item sits in the middle instead of hugging the left edge.
      true: "flex overflow-x-auto overflow-y-hidden overscroll-x-contain has-[>:only-child]:justify-center",
      mobile:
        "flex overflow-x-auto overflow-y-hidden overscroll-x-contain lg:flex-col lg:overflow-x-hidden lg:overflow-y-auto lg:overscroll-y-auto",
      false: "overflow-y-auto overflow-x-hidden overscroll-y-auto",
    },
    hasScrollbar: {
      true: "scrollbar-thin",
      false: "scrollbar-none",
    },
    isVisible: {
      true: "rounded-2xl border bg-sidebar/60 p-2",
      false: "",
    },
  },
  defaultVariants: { isHorizontal: false, isVisible: true, hasScrollbar: false },
});

export interface ScrollContainerProps extends React.HTMLAttributes<HTMLDivElement> {
  isVisible?: boolean;
  isHorizontal?: boolean | "mobile";
  /** Show a thin scrollbar. Defaults to false (hidden, still scrollable). */
  hasScrollbar?: boolean;
  /** Keep the view still when items are added or removed elsewhere. Defaults to true. */
  keepPosition?: boolean;
}

export const ScrollContainer = React.forwardRef<HTMLDivElement, ScrollContainerProps>(
  (
    {
      isVisible = true,
      isHorizontal = false,
      hasScrollbar = false,
      keepPosition = true,
      className,
      ...props
    },
    ref,
  ) => {
    const own = React.useRef<HTMLDivElement | null>(null);
    useScrollAnchor(own, keepPosition);
    const setRef = React.useCallback(
      (node: HTMLDivElement | null) => {
        own.current = node;
        if (typeof ref === "function") ref(node);
        else if (ref) ref.current = node;
      },
      [ref],
    );
    return (
      <motion.div
        ref={setRef}
        layoutScroll
        data-scroll-direction={
          isHorizontal === "mobile" ? "responsive" : isHorizontal ? "horizontal" : "vertical"
        }
        className={cn(
          scrollContainerVariants({ isVisible, isHorizontal, hasScrollbar }),
          // The hook does the anchoring, the same in every browser (Safari has no native scroll anchoring).
          keepPosition && "[overflow-anchor:none]",
          className,
        )}
        {...(props as unknown as HTMLMotionProps<"div">)}
      />
    );
  },
);
ScrollContainer.displayName = "ScrollContainer";

/** Within this many pixels of an edge counts as being at it. */
const EDGE = 2;

/**
 * Keeps what you're looking at still when the list changes (items added, removed or resized) somewhere else:
 *
 * - At the very start (top, or left for a side-scrolling row), you stay there, so an item added at the start shows.
 * - At the very end, you stay at the end, so an item added at the end shows. Not in a paged list (one holding a
 *   `[data-page-edge]`): there the end grows with older pages, and following it would load page after page.
 * - Anywhere else, the first item in view keeps its place on screen.
 *
 * Positions come from layout offsets, not on-screen boxes, so the list's slide-in animations don't throw it off.
 */
function useScrollAnchor(ref: React.RefObject<HTMLDivElement | null>, enabled: boolean) {
  React.useLayoutEffect(() => {
    const box = ref.current;
    if (!enabled || !box) return;
    // "mobile" rows switch axis at a breakpoint, so the axis is read each time.
    const sideways = () => getComputedStyle(box).overflowX !== "hidden";
    const scrollOf = (h: boolean) => (h ? box.scrollLeft : box.scrollTop);
    const rangeOf = (h: boolean) =>
      h ? box.scrollWidth - box.clientWidth : box.scrollHeight - box.clientHeight;
    /** An element's start inside the scrolled content (ignoring transforms), or null outside this box. */
    const startOf = (element: HTMLElement, h: boolean) => {
      let offset = 0;
      let node: HTMLElement | null = element;
      while (node && node !== box) {
        offset += h ? node.offsetLeft : node.offsetTop;
        node = node.offsetParent as HTMLElement | null;
      }
      return node === box ? offset : null;
    };
    /** The item at the start of the view: the deepest element that reaches past it, stopping at one that starts in view. */
    const firstInView = (h: boolean, scroll: number) => {
      let parent: Element = box;
      let chosen: HTMLElement | null = null;
      for (let depth = 0; depth < 8; depth++) {
        let next: HTMLElement | null = null;
        for (const child of Array.from(parent.children)) {
          if (!(child instanceof HTMLElement)) continue;
          const start = startOf(child, h);
          const length = h ? child.offsetWidth : child.offsetHeight;
          if (start === null || length === 0) continue;
          if (start + length > scroll) {
            next = child;
            break;
          }
        }
        if (!next) break;
        chosen = next;
        if ((startOf(next, h) ?? 0) >= scroll) break;
        parent = next;
      }
      return chosen;
    };

    let state = { atStart: true, atEnd: true, anchor: null as HTMLElement | null, offset: 0 };
    const measure = () => {
      const h = sideways();
      const scroll = scrollOf(h);
      const anchor = firstInView(h, scroll);
      state = {
        atStart: scroll <= EDGE,
        atEnd: rangeOf(h) - scroll <= EDGE,
        anchor,
        offset: anchor ? (startOf(anchor, h) ?? 0) - scroll : 0,
      };
    };
    const scrollTo = (h: boolean, value: number) => {
      if (h) box.scrollLeft = value;
      else box.scrollTop = value;
    };
    const adjust = () => {
      const h = sideways();
      const paged = box.querySelector("[data-page-edge]") !== null;
      if (state.atStart) {
        scrollTo(h, 0);
      } else if (state.atEnd && !paged) {
        scrollTo(h, rangeOf(h));
      } else if (state.anchor?.isConnected) {
        const start = startOf(state.anchor, h);
        if (start !== null && start - state.offset !== scrollOf(h))
          scrollTo(h, start - state.offset);
      }
      measure();
    };

    measure();
    box.addEventListener("scroll", measure, { passive: true });
    // Content size changes (rows growing, images loading) and the box's own size.
    const resize = new ResizeObserver(adjust);
    const observeChildren = () => {
      resize.disconnect();
      resize.observe(box);
      for (const child of Array.from(box.children)) resize.observe(child);
    };
    observeChildren();
    // Rows added or removed anywhere inside, before the browser paints.
    const mutations = new MutationObserver(() => {
      observeChildren();
      adjust();
    });
    mutations.observe(box, { childList: true, subtree: true });
    return () => {
      box.removeEventListener("scroll", measure);
      resize.disconnect();
      mutations.disconnect();
    };
  }, [ref, enabled]);
}

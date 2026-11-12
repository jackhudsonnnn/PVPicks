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
      mobile: "flex overflow-x-auto overflow-y-hidden overscroll-x-contain lg:flex-col lg:overflow-x-hidden lg:overflow-y-auto lg:overscroll-y-auto",
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
}

export const ScrollContainer = React.forwardRef<HTMLDivElement, ScrollContainerProps>(
  ({ isVisible = true, isHorizontal = false, hasScrollbar = false, className, ...props }, ref) => (
    <motion.div
      ref={ref}
      layoutScroll
      data-scroll-direction={isHorizontal === "mobile" ? "responsive" : isHorizontal ? "horizontal" : "vertical"}
      className={cn(scrollContainerVariants({ isVisible, isHorizontal, hasScrollbar }), className)}
      {...(props as unknown as HTMLMotionProps<"div">)}
    />
  ),
);
ScrollContainer.displayName = "ScrollContainer";

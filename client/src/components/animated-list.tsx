import * as React from "react";
import { AnimatePresence, motion, type HTMLMotionProps, type Transition } from "motion/react";

/**
 * Smooth enter/exit for items in a growing list (usually inside a `ScrollContainer`).
 *
 *   <AnimatedList>
 *     {items.map((i) => <AnimatedItem key={i.id}>…</AnimatedItem>)}
 *   </AnimatedList>
 *
 * - Items already there on first render don't animate; only later adds/removes do.
 * - Added items fade in while sliding into place; removed items fade and slide away,
 *   and the items around them glide to their new spot instead of jumping.
 * - Give the whole list a new `key` when the data set is swapped (another tab, another
 *   table) so it switches instantly instead of animating every row.
 * - The app-wide MotionConfig handles "Reduced motion": slides are dropped, fades stay.
 */
export function AnimatedList({ children }: { children: React.ReactNode }) {
  return (
    <AnimatePresence initial={false} mode="popLayout">
      {children}
    </AnimatePresence>
  );
}

const EASE_OUT = [0.22, 1, 0.36, 1] as const;

const TRANSITION: Transition = {
  duration: 0.32,
  ease: EASE_OUT,
  layout: { type: "spring", stiffness: 380, damping: 36, mass: 0.9 },
};

const EXIT_TRANSITION: Transition = { duration: 0.22, ease: EASE_OUT };

type AnimatedItemProps = Omit<HTMLMotionProps<"div">, "layout"> & {
  /** Render as a list item when the parent is a `<ul>`/`<ol>`. */
  as?: "div" | "li";
  /** Match the list direction so items slide the natural way. */
  isHorizontal?: boolean;
  /** Let neighbours glide when this item moves. Off for append-only feeds like chat. */
  layout?: boolean;
};

export const AnimatedItem = React.forwardRef<HTMLElement, AnimatedItemProps>(function AnimatedItem(
  { as = "div", isHorizontal = false, layout = true, style, ...props },
  ref,
) {
  const enter = isHorizontal ? { x: 24 } : { y: 12 };
  const leave = isHorizontal ? { y: -12 } : { x: -24 };
  const shared = {
    layout: layout ? ("position" as const) : false,
    initial: { opacity: 0, scale: 0.98, ...enter },
    animate: { opacity: 1, x: 0, y: 0, scale: 1, transition: TRANSITION },
    exit: { opacity: 0, scale: 0.98, ...leave, transition: EXIT_TRANSITION },
    transition: TRANSITION,
    style: { ...style, willChange: "transform, opacity" },
  };
  if (as === "li") {
    return (
      <motion.li
        ref={ref as React.Ref<HTMLLIElement>}
        {...shared}
        {...(props as HTMLMotionProps<"li">)}
      />
    );
  }
  return <motion.div ref={ref as React.Ref<HTMLDivElement>} {...shared} {...props} />;
});

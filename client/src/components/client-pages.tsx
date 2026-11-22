import { useEffect, useRef } from "react";
import { cn } from "@/lib/utils";

/**
 * The edge of a paged list: loads the next page when it scrolls into view (within `margin` of it), and is a plain
 * button for keyboards and browsers without IntersectionObserver. Put it after the last shown item.
 */
export function PageEdge({
  onMore,
  label,
  className,
  margin = "400px",
}: {
  onMore: () => void;
  label: string;
  className?: string;
  margin?: string;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const latest = useRef(onMore);
  latest.current = onMore;
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) latest.current();
      },
      { rootMargin: margin },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [margin]);
  return (
    <button
      ref={ref}
      type="button"
      onClick={onMore}
      className={cn(
        "shrink-0 rounded-xl border border-dashed px-3 py-2 text-xs text-muted-foreground transition-colors hover:text-foreground",
        className,
      )}
    >
      {label}
    </button>
  );
}

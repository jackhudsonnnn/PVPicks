import { useEffect, useRef } from "react";
import { LoaderCircle } from "lucide-react";

/** Observe within the actual list, not the page; re-arm after a page arrives. */
export function AutoPageSentinel({
  hasMore,
  pageKey,
  onLoadMore,
}: {
  hasMore: boolean;
  pageKey: string | number;
  onLoadMore: () => void;
}) {
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !hasMore) return;
    let requested = false;
    const observer = new IntersectionObserver(
      (entries) => {
        if (requested || !entries.some((entry) => entry.isIntersecting)) return;
        requested = true;
        observer.disconnect();
        onLoadMore();
      },
      { root: el.closest('[data-scroll-direction="vertical"]'), rootMargin: "0px 0px 160px 0px" },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, pageKey, onLoadMore]);

  if (!hasMore) return null;
  return (
    <div
      ref={sentinel}
      role="status"
      aria-label="Loading more tickets"
      className="col-span-full flex h-8 items-center justify-center text-muted-foreground"
    >
      <LoaderCircle aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" />
    </div>
  );
}

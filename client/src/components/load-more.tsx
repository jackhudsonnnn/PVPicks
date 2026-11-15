import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Spinner } from "./loading";

/**
 * "Load more" for paged lists. Also loads on its own when scrolled into view (where IntersectionObserver exists), so
 * long lists page as you scroll; the button stays for keyboards and older browsers.
 */
export function LoadMore({ loading, onLoad }: { loading: boolean; onLoad: () => void }) {
  const ref = useRef<HTMLButtonElement>(null);
  const latest = useRef({ loading, onLoad });
  latest.current = { loading, onLoad };
  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !latest.current.loading)
        latest.current.onLoad();
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return (
    <Button ref={ref} variant="outline" className="w-full" disabled={loading} onClick={onLoad}>
      {loading && <Spinner />} Load more
    </Button>
  );
}

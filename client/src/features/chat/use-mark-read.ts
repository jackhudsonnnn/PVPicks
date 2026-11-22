import { useEffect, useRef } from "react";
import { isUnauthorized } from "@/lib/api-client";
import { markRead } from "./api";

/** At most one read-position write this often; a busy chat would otherwise spend the per-account write budget. */
export const READ_INTERVAL_MS = 3000;

/**
 * Advances the read position to the newest message on screen: at once for the first, then at most every
 * {@link READ_INTERVAL_MS}, always ending on the newest.
 */
export function useMarkRead(tableId: string, latestId: string | undefined, expire: () => void) {
  const marked = useRef<string | null>(null);
  const lastSent = useRef(0);
  const expireRef = useRef(expire);
  expireRef.current = expire;
  useEffect(() => {
    if (!latestId || marked.current === latestId) return;
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    const send = () => {
      marked.current = latestId;
      lastSent.current = Date.now();
      markRead(tableId, latestId, crypto.randomUUID()).catch((error: unknown) => {
        // The position only moves forward and the next message retries it; only a lost session matters.
        if (isUnauthorized(error)) expireRef.current();
        else marked.current = null;
      });
    };
    const wait = lastSent.current + READ_INTERVAL_MS - Date.now();
    if (wait <= 0) {
      send();
      return;
    }
    // A newer message replaces this pending write, so the newest one is what gets sent.
    const timer = setTimeout(send, wait);
    return () => clearTimeout(timer);
  }, [tableId, latestId]);
}

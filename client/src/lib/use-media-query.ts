import { useCallback, useSyncExternalStore } from "react";

/** Whether a CSS media query matches, updating when it changes. False during server rendering. */
export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = window.matchMedia(query);
      list.addEventListener("change", onChange);
      return () => list.removeEventListener("change", onChange);
    },
    [query],
  );
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(query).matches,
    () => false,
  );
}

/**
 * Where Home shows its Tickets box: beside the tables from Tailwind's `lg` (1024px), and below them on narrower
 * screens that are tall enough for both (790px). Shorter screens use the Tickets tab.
 */
export const HOME_TICKETS = "(min-width: 1024px), (min-height: 790px)";

/** When the desktop sidebar shows: Tailwind's `nav` breakpoint (68.75rem, in styles.css). */
export const NAV_SIDEBAR = "(min-width: 68.75rem)";

/** When the table page shows the felt beside its side panel: Tailwind's `split` breakpoint (90.9375rem, in styles.css). */
export const TABLE_SPLIT = "(min-width: 90.9375rem)";

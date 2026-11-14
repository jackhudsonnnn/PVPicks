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

/** Tailwind's `lg` breakpoint, where Home shows its Tickets column. */
export const DESKTOP = "(min-width: 1024px)";

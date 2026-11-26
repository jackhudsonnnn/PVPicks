import { useEffect, useRef } from "react";

/**
 * Keeps whoever's turn it is in view in a felt's sideways-scrolling people row: put the returned ref on a wrapper
 * around the row, and `data-to-act` on the acting tile. Re-centres whenever `turn` changes.
 */
export function useActingInView(turn: unknown) {
  const row = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const box = row.current;
    const tile = box?.querySelector<HTMLElement>("[data-to-act]");
    if (!box || !tile) return;
    const left = tile.offsetLeft - box.offsetLeft;
    const list = box.firstElementChild as HTMLElement | null;
    if (list?.scrollTo)
      list.scrollTo({
        left: left - list.clientWidth / 2 + tile.clientWidth / 2,
        behavior: "smooth",
      });
  }, [turn]);
  return row;
}

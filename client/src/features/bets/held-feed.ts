import { useEffect, useRef, useState } from "react";

/** Within this many pixels of the top of the page counts as at the top. */
const TOP = 8;

/** Whether the page is scrolled to (or within a few pixels of) the top. */
function useAtTop() {
  const [atTop, setAtTop] = useState(() => typeof window === "undefined" || window.scrollY <= TOP);
  useEffect(() => {
    const check = () => setAtTop(window.scrollY <= TOP);
    check();
    window.addEventListener("scroll", check, { passive: true });
    return () => window.removeEventListener("scroll", check);
  }, []);
  return atTop;
}

/**
 * Which items to show, given the frozen order (null when live). Items already shown keep their place, with their
 * latest data, or their last known data once they've left the list. Items that come after the last shown one (the
 * next page) are added at the end; any others are new and held back.
 */
export function holdFeed<T extends { id: string }>(
  items: T[],
  frozen: string[] | null,
  known: Map<string, T>,
): { shown: T[]; held: number } {
  if (!frozen) return { shown: items, held: 0 };
  const inFrozen = new Set(frozen);
  const latest = new Map(items.map((item) => [item.id, item]));
  const shown = frozen.flatMap((id) => {
    const item = latest.get(id) ?? known.get(id);
    return item ? [item] : [];
  });
  let last = -1;
  items.forEach((item, i) => {
    if (inFrozen.has(item.id)) last = i;
  });
  let held = 0;
  items.forEach((item, i) => {
    if (inFrozen.has(item.id)) return;
    if (i > last) shown.push(item);
    else held++;
  });
  return { shown, held };
}

/**
 * A newest-first feed on a page that scrolls (owner decision, 2026-11-23): while you're scrolled away from the top
 * the list holds still. New items aren't slotted in above you (which would shift everything, and in a two-column
 * layout reflow the columns), only counted; they appear once you're back at the top, or when you jump there with
 * `showNew`. Items that leave the feed stay put until then. Changing `resetKey` (another tab) goes live again.
 */
export function useHeldFeed<T extends { id: string }>(items: T[], resetKey: string) {
  const atTop = useAtTop();
  const [frozen, setFrozen] = useState<string[] | null>(null);
  const known = useRef(new Map<string, T>());
  for (const item of items) known.current.set(item.id, item);
  const { shown, held } = holdFeed(items, frozen, known.current);
  // Freeze what's shown when you leave the top; go live when you're back.
  const shownIds = shown.map((item) => item.id).join(",");
  useEffect(() => {
    if (atTop) setFrozen(null);
    else setFrozen((current) => current ?? (shownIds ? shownIds.split(",") : []));
    // Only when you cross the top: the frozen list itself shouldn't follow every change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atTop]);
  useEffect(() => {
    setFrozen(null);
    known.current = new Map();
  }, [resetKey]);
  // Grows with "Load more" pages appended at the end while frozen.
  useEffect(() => {
    if (frozen && shownIds.split(",").length > frozen.length) setFrozen(shownIds.split(","));
  }, [frozen, shownIds]);
  const showNew = () => window.scrollTo({ top: 0, behavior: "smooth" });
  return { shown, held, showNew };
}

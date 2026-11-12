import { useEffect, useState } from "react";
import { isArray, onStorageChange, readJson, writeJson } from "./storage";

/**
 * A list saved in this browser that every component and tab sees, standing in for a
 * server table until one exists (reports, feedback). Newest items come first.
 * TODO(backend): swap for API calls with the same `useItems`/`add`/`update` shape.
 */
export function createLocalCollection<T extends { id: string }>(
  key: string,
  options: { seed?: () => T[] } = {},
) {
  const event = `${key}:changed`;

  function load(): T[] {
    const saved = readJson<T[] | null>(key, null, isArray);
    if (saved) return saved;
    const seeded = options.seed?.() ?? [];
    if (seeded.length) writeJson(key, seeded);
    return seeded;
  }

  function save(items: T[]) {
    writeJson(key, items);
    window.dispatchEvent(new Event(event));
  }

  function useItems() {
    const [items, setItems] = useState<T[]>([]);
    useEffect(() => {
      const sync = () => setItems(load());
      sync();
      window.addEventListener(event, sync);
      const off = onStorageChange(key, sync);
      return () => {
        window.removeEventListener(event, sync);
        off();
      };
    }, []);
    return items;
  }

  return {
    load,
    useItems,
    add: (item: T) => save([item, ...load()]),
    update: (id: string, change: (item: T) => T) =>
      save(load().map((x) => (x.id === id ? change(x) : x))),
  };
}

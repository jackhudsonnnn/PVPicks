/**
 * Safe browser storage. Every call is a no-op (or returns the fallback) during SSR,
 * in private modes that block storage, or when saved data is missing or broken,
 * so a bad value can never crash the app. Keys live in `features/auth/storage-keys.ts`.
 */

function store(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

/**
 * Reads and parses a saved JSON value. `accept` checks its shape, since anything
 * could be in storage; values that fail it are ignored.
 */
export function readJson<T>(key: string, fallback: T, accept?: (v: unknown) => boolean): T {
  try {
    const raw = store()?.getItem(key);
    if (raw == null) return fallback;
    const value: unknown = JSON.parse(raw);
    if (accept ? !accept(value) : value == null) return fallback;
    return value as T;
  } catch {
    return fallback;
  }
}

/** Saves a value as JSON. Returns false when storage is unavailable or full. */
export function writeJson(key: string, value: unknown): boolean {
  try {
    const s = store();
    if (!s) return false;
    s.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function removeKeys(...keys: string[]) {
  const s = store();
  for (const k of keys) {
    try {
      s?.removeItem(k);
    } catch {
      /* storage blocked */
    }
  }
}

/** Every saved key and its size, for the admin health page. */
export function storageUsage(prefix = ""): { key: string; bytes: number }[] {
  const s = store();
  if (!s) return [];
  const out: { key: string; bytes: number }[] = [];
  try {
    for (let i = 0; i < s.length; i++) {
      const key = s.key(i);
      if (key === null || !key.startsWith(prefix)) continue;
      out.push({ key, bytes: (key.length + (s.getItem(key)?.length ?? 0)) * 2 });
    }
  } catch {
    /* storage blocked */
  }
  return out;
}

/** Calls `fn` when another tab changes `key` (or clears storage). Returns an unsubscribe. */
export function onStorageChange(key: string, fn: () => void) {
  if (typeof window === "undefined") return () => {};
  const handler = (e: StorageEvent) => {
    if (e.key === key || e.key === null) fn();
  };
  window.addEventListener("storage", handler);
  return () => window.removeEventListener("storage", handler);
}

export const isArray = (v: unknown): v is unknown[] => Array.isArray(v);
export const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

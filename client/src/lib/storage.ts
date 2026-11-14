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

export const isArray = (v: unknown): v is unknown[] => Array.isArray(v);
export const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

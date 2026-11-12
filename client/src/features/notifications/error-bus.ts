/** Tiny pub/sub so non-React code (store commands, window errors) can post error notices to the inbox. */
type ErrorPayload = { title: string; description?: string | undefined };
const listeners = new Set<(e: ErrorPayload) => void>();

export function emitError(title: string, description?: string) {
  listeners.forEach((l) => l({ title, description }));
}

export function onError(fn: (e: ErrorPayload) => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

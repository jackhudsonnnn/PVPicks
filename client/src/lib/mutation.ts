import { useEffect, useRef, useState } from "react";
import { z } from "zod";
import { BackendError, backendErrorMessage } from "./backend-api";

const safeRetryMs = 23 * 60 * 60 * 1000;

export function useRetryableMutation<Intent>({
  storageKey,
  intentSchema,
  execute,
  refresh,
  expire,
  checkDescription,
  errorMessage = backendErrorMessage,
}: {
  storageKey: string;
  intentSchema: z.ZodType<Intent>;
  /** Performs the action; may return a message to show on success instead of the default. */
  execute: (intent: Intent, key: string) => Promise<string | void>;
  refresh: () => Promise<void>;
  expire: () => void;
  checkDescription: string;
  errorMessage?: (error: BackendError) => string;
}) {
  const pendingSchema = z.object({
    intent: intentSchema,
    key: z.guid(),
    createdAt: z.number().int().nonnegative(),
  });
  type Pending = z.infer<typeof pendingSchema>;
  // Saved in localStorage so every tab of this browser sees the same uncertain action and
  // blocks new ones until it is retried or cleared. Other devices reconcile from server reads.
  function load(): { pending: Pending | null; error: string | null } {
    try {
      const raw = localStorage.getItem(storageKey);
      if (raw === null) return { pending: null, error: null };
      const result = pendingSchema.safeParse(JSON.parse(raw));
      return result.success
        ? { pending: result.data, error: null }
        : { pending: null, error: "The saved retry is invalid." };
    } catch {
      return { pending: null, error: "Retry storage is unavailable or invalid." };
    }
  }
  const [saved, setSaved] = useState(load);
  useEffect(() => {
    function sync(event: StorageEvent) {
      if (event.key === storageKey || event.key === null) setSaved(load());
    }
    window.addEventListener("storage", sync);
    return () => window.removeEventListener("storage", sync);
    // load only reads storageKey and the schema, which are fixed per key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const locked = useRef(false);

  function persist(pending: Pending | null): boolean {
    try {
      if (pending) localStorage.setItem(storageKey, JSON.stringify(pending));
      else localStorage.removeItem(storageKey);
      setSaved({ pending, error: null });
      return true;
    } catch {
      setSaved((current) => ({ ...current, error: "Retry storage is unavailable." }));
      return false;
    }
  }

  async function run(pending: Pending, retrying: boolean) {
    if (locked.current) return;
    const age = Date.now() - pending.createdAt;
    if (age < 0 || age >= safeRetryMs) {
      setMessage(
        `This action is too old to retry safely. Check ${checkDescription} before clearing it.`,
      );
      return;
    }
    locked.current = true;
    setBusy(true);
    setMessage(null);
    try {
      const note = await execute(pending.intent, pending.key);
      if (persist(null)) {
        setMessage(note || "Action completed. Refreshing.");
        void refresh();
      }
    } catch (error) {
      if (error instanceof BackendError && [400, 401, 403, 404, 409].includes(error.status ?? 0)) {
        if (retrying) {
          if (error.status === 401) expire();
          else {
            setMessage(
              `The retry was rejected, but the original action may have succeeded. Check ${checkDescription} before clearing it.`,
            );
            void refresh();
          }
        } else if (persist(null)) {
          if (error.status === 401) expire();
          else {
            setMessage(errorMessage(error));
            void refresh();
          }
        } else if (error.status === 401) expire();
      } else {
        setMessage(
          "The action's outcome is unknown. Retry the same action before making other changes.",
        );
      }
    } finally {
      locked.current = false;
      setBusy(false);
    }
  }

  return {
    /** Hides the last result message. */
    dismiss: () => setMessage(null),
    pending: saved.pending,
    storageError: saved.error,
    message,
    busy,
    start: (intent: Intent) => {
      if (locked.current || saved.pending || saved.error) return;
      const pending = { intent, key: crypto.randomUUID(), createdAt: Date.now() };
      if (persist(pending)) void run(pending, false);
    },
    retry: () => {
      if (saved.pending && !saved.error) void run(saved.pending, true);
    },
    clearAfterReview: () => {
      if (!busy && saved.pending && persist(null)) {
        setMessage(
          `Saved action cleared. Refresh and verify ${checkDescription} before making changes.`,
        );
        void refresh();
      }
    },
  };
}

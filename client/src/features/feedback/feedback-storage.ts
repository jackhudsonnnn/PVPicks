import { useCallback } from "react";
import { SHARED_KEYS } from "@/features/auth/storage-keys";
import { createLocalCollection } from "@/lib/local-collection";
import { newId } from "@/lib/ids";
import type { Feedback } from "./feedback";

/**
 * Feedback is saved in this browser and shared across demo accounts so the Admin inbox sees it.
 * TODO(backend): send to a server table (or email).
 */
const feedback = createLocalCollection<Feedback>(SHARED_KEYS.feedback);

export function useFeedback() {
  const items = feedback.useItems();
  const send = useCallback((f: Omit<Feedback, "id" | "createdAt">) => {
    feedback.add({ ...f, id: newId("f"), createdAt: Date.now() });
  }, []);
  return { items, send };
}

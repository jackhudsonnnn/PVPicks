import { z } from "zod";
import { mutationRequest } from "./backend-api";
import { useRetryableMutation } from "./backend-mutation";

export const FEEDBACK_SENT = "Thanks, it's in.";

const intentSchema = z.object({
  kind: z.enum(["mode", "perk", "gif", "bug", "other"]),
  text: z.string().min(1).max(4000),
  link: z.string().max(4000).optional(),
});

/** Sends Guide feedback to POST /feedback with retry-key safety. */
export function useFeedbackActions(userId: string, expire: () => void) {
  return useRetryableMutation({
    storageKey: `p2picks:feedback-action:${userId}`,
    intentSchema,
    refresh: async () => {},
    expire,
    checkDescription: "nothing: feedback can't be listed yet, so retrying is the safe choice",
    execute: async (intent, key) => {
      await mutationRequest("/feedback", "POST", key, intent);
      return FEEDBACK_SENT;
    },
  });
}

import { z } from "zod";
import { backendErrorMessage, idSchema } from "@/lib/api-client";
import { useRetryableMutation } from "@/lib/mutation";
import { acknowledgeReview, createSettle, rejectSettle } from "./api";

const uuid = idSchema;
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("review"), other: uuid, revision: uuid }),
  z.object({
    kind: z.literal("settle"),
    other: uuid,
    settle: z.enum(["sent", "received", "forgiven"]),
    lineIds: z.array(uuid).min(1).max(500),
  }),
  z.object({ kind: z.literal("reject"), other: uuid, settleId: uuid }),
]);

export type SettlementIntent = z.infer<typeof intentSchema>;

/** Reviewing a balance change, settling chosen receipts, and rejecting a send, with retry-key safety. */
export function useSettlementActions(
  userId: string,
  refresh: () => Promise<void>,
  expire: () => void,
) {
  return useRetryableMutation({
    storageKey: `p2picks:settlement-action:${userId}`,
    intentSchema,
    refresh,
    expire,
    checkDescription: "the ledger",
    errorMessage: (error) =>
      error.code === "stale_review" || error.code === "stale_selection"
        ? "Something changed. Check the refreshed ledger before trying again."
        : error.code === "settlement_review_required"
          ? "Review the balance change before settling."
          : backendErrorMessage(error),
    execute: async (intent, key) => {
      if (intent.kind === "review") {
        await acknowledgeReview(intent.other, intent.revision, key);
        return;
      }
      if (intent.kind === "settle") {
        await createSettle(intent.other, intent.settle, intent.lineIds, key);
        return intent.settle === "sent"
          ? "Sent. It's settled unless they reject it within 48 hours."
          : intent.settle === "forgiven"
            ? "Forgiven."
            : "Marked received.";
      }
      await rejectSettle(intent.settleId, key);
      return "Rejected. Those receipts are open again.";
    },
  });
}

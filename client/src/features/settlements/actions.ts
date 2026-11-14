import { z } from "zod";
import { backendErrorMessage } from "./backend-api";
import { useRetryableMutation } from "./backend-mutation";
import { acknowledgeReview, transitionGroup, transitionLine } from "./settlement-api";

const uuid = z.guid();
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("review"), other: uuid, revision: uuid }),
  z.object({
    kind: z.literal("line"),
    other: uuid,
    lineId: uuid,
    action: z.enum(["sent", "confirm", "dispute", "forgive", "ask-forgive", "decline-forgive"]),
    revision: z.number().int().min(0).max(2147483647),
  }),
  z.object({
    kind: z.literal("group"),
    other: uuid,
    action: z.enum(["sent", "paid", "unpaid", "forgiven", "ask", "decline"]),
    revision: z.string().min(1),
  }),
]);

export type SettlementIntent = z.infer<typeof intentSchema>;

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
    checkDescription: "receipt history",
    errorMessage: (error) =>
      error.code === "stale_review" || error.code === "stale_revision"
        ? "The receipt or warning changed. Review the refreshed information before trying again."
        : error.code === "settlement_review_required"
          ? "Review the balance-change warning before sending or asking for forgiveness."
          : backendErrorMessage(error),
    execute: async (intent, key) => {
      if (intent.kind === "review") await acknowledgeReview(intent.other, intent.revision, key);
      else if (intent.kind === "line")
        await transitionLine(
          userId,
          intent.other,
          intent.lineId,
          intent.action,
          intent.revision,
          key,
        );
      else await transitionGroup(intent.other, intent.action, intent.revision, key);
    },
  });
}

import { z } from "zod";
import { BackendError, backendErrorMessage, mutationRequest, readProposedBet } from "./backend-api";
import { useRetryableMutation } from "./backend-mutation";

const uuid = z.guid();
export const proposalSchema = z.object({
  question: z.string().min(1),
  choices: z.array(z.string().min(1)).min(2).max(6),
  wagerCents: z.number().int().min(0).max(1000),
  durationSec: z.number().int().min(30).max(600),
  forFun: z.boolean(),
  settledBy: z.enum(["anyone", "non_participants", "hosts", "me"]),
});
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("propose"), tableId: uuid, proposal: proposalSchema }),
  z.object({ kind: z.literal("pick"), tableId: uuid, betId: uuid, choice: z.string().min(1) }),
  z.object({ kind: z.literal("clear"), tableId: uuid, betId: uuid }),
]);
export type BetIntent = z.infer<typeof intentSchema>;

export async function executeBetAction(userId: string, intent: BetIntent, key: string) {
  if (!uuid.safeParse(userId).success || !intentSchema.safeParse(intent).success)
    throw new BackendError("Invalid bet action.");
  if (intent.kind === "propose") {
    const response = await mutationRequest(
      `/tables/${encodeURIComponent(intent.tableId)}/bets`,
      "POST",
      key,
      { modeId: "u2pick", config: {}, ...intent.proposal },
    );
    await readProposedBet(response, intent.tableId, userId);
  } else {
    await mutationRequest(
      `/bets/${encodeURIComponent(intent.betId)}/pick`,
      intent.kind === "pick" ? "PUT" : "DELETE",
      key,
      intent.kind === "pick" ? { choice: intent.choice } : undefined,
    );
  }
}

export function useBetActions(userId: string, refresh: () => Promise<void>, expire: () => void) {
  return useRetryableMutation({
    storageKey: `p2picks:bet-action:${userId}`,
    intentSchema,
    refresh,
    expire,
    checkDescription: "the bet list and your pick",
    errorMessage: (error) =>
      error.code === "bet_closed"
        ? "The pick window has closed. Refresh the bet before continuing."
        : error.code === "debt_cap_exceeded"
          ? "This action would exceed your debt cap."
          : backendErrorMessage(error),
    execute: (intent, key) => executeBetAction(userId, intent, key),
  });
}

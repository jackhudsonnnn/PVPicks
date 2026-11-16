import { useRef } from "react";
import { z } from "zod";
import { backendErrorMessage, idSchema } from "@/lib/api-client";
import { useRetryableMutation } from "@/lib/mutation";
import { createPoll, sendChatMessage, setReaction, setVote } from "./api";

const uuid = idSchema;
const intentSchema = z.discriminatedUnion("kind", [
  z.object({
    kind: z.literal("send"),
    tableId: uuid,
    message: z.discriminatedUnion("kind", [
      z.object({ kind: z.literal("text"), text: z.string().min(1).max(8000) }),
      z.object({ kind: z.literal("gif"), gifId: z.string().regex(/^g\d{1,2}$/) }),
    ]),
  }),
  z.object({
    kind: z.literal("poll"),
    tableId: uuid,
    question: z.string().min(1).max(800),
    options: z.array(z.string().min(1).max(320)).min(2).max(6),
  }),
  z.object({
    kind: z.literal("vote"),
    tableId: uuid,
    messageId: uuid,
    option: z.number().int().min(0).max(5).nullable(),
  }),
  z.object({
    kind: z.literal("react"),
    tableId: uuid,
    messageId: uuid,
    emoji: z.string().min(1).max(64).nullable(),
  }),
]);

type ChatIntent = z.infer<typeof intentSchema>;

/** Sending, polls and reacting, with the same retry-key safety as bets and receipts. */
export function useChatActions(
  userId: string,
  refresh: (intent: ChatIntent | null) => Promise<void>,
  expire: () => void,
) {
  // The intent being executed, so the refresh after it can target the affected message.
  const last = useRef<ChatIntent | null>(null);
  return useRetryableMutation({
    storageKey: `p2picks:chat-action:${userId}`,
    intentSchema,
    refresh: () => refresh(last.current),
    expire,
    checkDescription: "the chat",
    errorMessage: (error) =>
      error.status === 403
        ? "You don't have permission to do that at this table."
        : error.status === 404
          ? "That message or table is no longer available."
          : backendErrorMessage(error),
    execute: async (intent, key) => {
      last.current = intent;
      if (intent.kind === "send") await sendChatMessage(intent.tableId, intent.message, key);
      else if (intent.kind === "poll")
        await createPoll(
          intent.tableId,
          { question: intent.question, options: intent.options },
          key,
        );
      else if (intent.kind === "vote") await setVote(intent.messageId, intent.option, key);
      else await setReaction(intent.messageId, intent.emoji, key);
    },
  });
}

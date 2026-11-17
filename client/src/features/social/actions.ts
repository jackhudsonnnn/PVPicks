import { z } from "zod";
import { BackendError, backendErrorMessage, idSchema } from "@/lib/api-client";
import { useRetryableMutation } from "@/lib/mutation";
import { respondToInvite, setBlock, setFollow, submitReport } from "./api";

const uuid = idSchema;
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("follow"), userId: uuid, follow: z.boolean() }),
  z.object({ kind: z.literal("block"), userId: uuid, block: z.boolean() }),
  z.object({
    kind: z.literal("report"),
    targetId: uuid,
    reason: z.enum(["harassment", "settlement", "spam", "offensive", "other"]),
    note: z.string().max(500),
    tableId: uuid.optional(),
    messageId: uuid.optional(),
  }),
]);

function socialErrorMessage(error: BackendError) {
  if (error.status === 429) return "You've sent a lot of reports today. Try again tomorrow.";
  if (error.status === 404) return "That person or invitation is no longer available.";
  return backendErrorMessage(error);
}

/** Following, blocking and reports, with retry-key safety. Invitations have their own ({@link useInviteActions}). */
export function useSocialActions(userId: string, refresh: () => Promise<void>, expire: () => void) {
  return useRetryableMutation({
    storageKey: `p2picks:social-action:${userId}`,
    intentSchema,
    refresh,
    expire,
    checkDescription: "your follows, blocks and reports",
    errorMessage: socialErrorMessage,
    execute: async (intent, key) => {
      if (intent.kind === "follow") {
        await setFollow(intent.userId, intent.follow, key);
        return intent.follow ? "Following." : "Unfollowed.";
      }
      if (intent.kind === "block") {
        await setBlock(intent.userId, intent.block, key);
        return intent.block
          ? "Blocked. They can't see your messages, add you or be added by you."
          : "Unblocked. Follows are not restored.";
      }
      const { kind: _kind, ...report } = intent;
      await submitReport(report, key);
      return "Report sent. Thanks for telling us.";
    },
  });
}

const inviteIntentSchema = z.object({
  kind: z.literal("invite"),
  inviteId: uuid,
  tableId: uuid,
  accept: z.boolean(),
});

/**
 * Accepting or declining table requests, with retry-key safety. Its own storage key, so the sidebar's requests can
 * answer while a page's social actions (table page, Friends) are mounted too; mount one at a time. `onJoined` runs
 * after an accept goes through (it opens the table).
 */
export function useInviteActions(
  userId: string,
  refresh: () => Promise<void>,
  expire: () => void,
  onJoined?: (tableId: string) => void,
) {
  return useRetryableMutation({
    storageKey: `p2picks:invite-action:${userId}`,
    intentSchema: inviteIntentSchema,
    refresh,
    expire,
    checkDescription: "your tables and requests",
    errorMessage: socialErrorMessage,
    execute: async (intent, key) => {
      await respondToInvite(intent.inviteId, intent.accept, key);
      if (intent.accept) onJoined?.(intent.tableId);
      return intent.accept ? "Joined the table." : "Request declined.";
    },
  });
}

export type InviteActions = ReturnType<typeof useInviteActions>;

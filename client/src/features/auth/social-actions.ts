import { z } from "zod";
import { BackendError, backendErrorMessage } from "./backend-api";
import { useRetryableMutation } from "./backend-mutation";
import { respondToInvite, setBlock, setFollow } from "./social-api";

const uuid = z.guid();
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("follow"), userId: uuid, follow: z.boolean() }),
  z.object({ kind: z.literal("block"), userId: uuid, block: z.boolean() }),
  z.object({ kind: z.literal("invite"), inviteId: uuid, accept: z.boolean() }),
]);

export type SocialIntent = z.infer<typeof intentSchema>;

function socialErrorMessage(error: BackendError) {
  if (error.status === 404) return "That person or invitation is no longer available.";
  return backendErrorMessage(error);
}

/** Following, blocking and invitation responses, with retry-key safety. */
export function useSocialActions(userId: string, refresh: () => Promise<void>, expire: () => void) {
  return useRetryableMutation({
    storageKey: `p2picks:social-action:${userId}`,
    intentSchema,
    refresh,
    expire,
    checkDescription: "your follows, blocks and invitations",
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
      await respondToInvite(intent.inviteId, intent.accept, key);
      return intent.accept ? "Joined the table." : "Invitation declined.";
    },
  });
}

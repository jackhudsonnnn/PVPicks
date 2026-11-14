import { z } from "zod";
import { BackendError, backendErrorMessage } from "./backend-api";
import { useRetryableMutation } from "./backend-mutation";
import { putDebtCap, putInvitePreferences, updateProfile } from "./preferences-api";

const mode = z.enum(["open", "request", "closed"]);
const intentSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("invites"), friends: mode, following: mode }),
  z.object({
    kind: z.literal("debt-cap"),
    capCents: z.number().int().min(0).max(2147483600).multipleOf(100).nullable(),
  }),
  z.object({
    kind: z.literal("profile"),
    username: z.string().min(1).max(64).optional(),
    hue: z.number().int().min(1).max(5).optional(),
  }),
]);

export type PreferencesIntent = z.infer<typeof intentSchema>;

function preferencesErrorMessage(error: BackendError) {
  if (error.code === "username_taken") return "Someone already has that username.";
  return backendErrorMessage(error);
}

/** Invite preferences, debt cap and profile changes, with retry-key safety. */
export function usePreferencesActions(
  userId: string,
  refresh: () => Promise<void>,
  expire: () => void,
) {
  return useRetryableMutation({
    storageKey: `p2picks:preferences-action:${userId}`,
    intentSchema,
    refresh,
    expire,
    checkDescription: "your preferences",
    errorMessage: preferencesErrorMessage,
    execute: async (intent, key) => {
      switch (intent.kind) {
        case "invites":
          await putInvitePreferences({ friends: intent.friends, following: intent.following }, key);
          return "Saved. Pending invitations that no longer qualify were cancelled.";
        case "debt-cap":
          await putDebtCap(intent.capCents, key);
          return intent.capCents === null ? "Debt cap turned off." : "Debt cap saved.";
        case "profile":
          await updateProfile(
            {
              ...(intent.username === undefined ? {} : { username: intent.username }),
              ...(intent.hue === undefined ? {} : { hue: intent.hue }),
            },
            key,
          );
          return intent.username === undefined
            ? "Color saved."
            : "Username saved. Use it the next time you sign in.";
      }
    },
  });
}

import { z } from "zod";
import { mutationRequest, read, request, userSchema, type BackendUser } from "./backend-api";

const mode = z.enum(["open", "request", "closed"]);
const invitePreferencesSchema = z.object({ friends: mode, following: mode });

export type InviteMode = z.infer<typeof mode>;
export type InvitePreferences = z.infer<typeof invitePreferencesSchema>;

export async function getInvitePreferences(signal?: AbortSignal): Promise<InvitePreferences> {
  return read(
    await request("/me/invite-preferences", signal ? { signal } : undefined),
    invitePreferencesSchema,
  );
}

export async function putInvitePreferences(preferences: InvitePreferences, key: string) {
  await mutationRequest("/me/invite-preferences", "PUT", key, preferences);
}

/** The cap in cents, or null when there is no cap. */
export async function getDebtCap(signal?: AbortSignal): Promise<number | null> {
  const result = await read(
    await request("/me/debt-cap", signal ? { signal } : undefined),
    z.object({ capCents: z.number().int().min(0).nullable() }),
  );
  return result.capCents;
}

export async function putDebtCap(capCents: number | null, key: string) {
  await mutationRequest("/me/debt-cap", "PUT", key, { capCents });
}

/** Current debt in cents: negative table balances plus negative open receipt nets. */
export async function getDebt(signal?: AbortSignal): Promise<number> {
  const result = await read(
    await request("/me/debt", signal ? { signal } : undefined),
    z.object({ debtCents: z.number().int().min(0) }),
  );
  return result.debtCents;
}

export async function updateProfile(
  change: { username?: string; hue?: number },
  key: string,
): Promise<BackendUser> {
  return read(await mutationRequest("/me/profile", "PATCH", key, change), userSchema);
}

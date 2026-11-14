import { z } from "zod";
import { BackendError, idSchema, read, request } from "@/lib/api-client";

export const userSchema = z.object({
  id: z.guid(),
  username: z.string(),
  hue: z.number().int().min(1).max(5),
});

const profileSchema = z.union([
  userSchema,
  z.object({
    id: z.guid(),
    displayName: z.literal("Deleted user"),
    deleted: z.literal(true),
  }),
]);

export type User = z.infer<typeof userSchema>;
export type UserProfile = z.infer<typeof profileSchema>;

export async function getProfile(id: string, signal?: AbortSignal): Promise<UserProfile> {
  if (!idSchema.safeParse(id).success) throw new BackendError("Profile not found.", 404);
  const profile = await read(
    await request(`/users/${encodeURIComponent(id)}`, signal ? { signal } : undefined),
    profileSchema,
  );
  if (profile.id !== id) throw new BackendError("The backend returned an unexpected response.");
  return profile;
}

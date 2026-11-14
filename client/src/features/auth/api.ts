import { z } from "zod";
import { BackendError, csrf, read, request, isUnauthorized } from "@/lib/api-client";
import { userSchema } from "@/features/people";

const currentUserSchema = userSchema.extend({ role: z.enum(["PLAYER", "ADMIN"]) });

/** The signed-in account; admins get the admin view because they cannot use player APIs. */
export type CurrentUser = z.infer<typeof currentUserSchema>;

export async function currentUser(signal?: AbortSignal): Promise<CurrentUser | null> {
  try {
    return await read(await request("/me", signal ? { signal } : undefined), currentUserSchema);
  } catch (error) {
    if (isUnauthorized(error)) return null;
    throw error;
  }
}

export async function signIn(username: string, password: string): Promise<CurrentUser> {
  const token = await csrf();
  await request("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
    body: JSON.stringify({ username, password }),
  });
  const user = await currentUser();
  if (!user)
    throw new BackendError("The session could not be verified. Please try signing in again.");
  return user;
}

export async function signOut(): Promise<void> {
  const token = await csrf();
  await request("/auth/logout", { method: "POST", headers: { "X-CSRF-TOKEN": token } });
}

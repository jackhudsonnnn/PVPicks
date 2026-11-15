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

const demoAccountSchema = userSchema.extend({
  role: z.enum(["PLAYER", "ADMIN"]),
  frozen: z.boolean(),
});
const demoAccountsSchema = z.object({
  main: z.array(demoAccountSchema),
  others: z.array(demoAccountSchema),
});

export type DemoAccount = z.infer<typeof demoAccountSchema>;
export type DemoAccounts = z.infer<typeof demoAccountsSchema>;

/**
 * The demo's sign-in picker: the main accounts, then demo users whose username starts with `query`. Null when the
 * backend has no demo (404), so the password form shows instead.
 */
export async function demoAccounts(
  query: string,
  signal?: AbortSignal,
): Promise<DemoAccounts | null> {
  try {
    const path = `/dev/demo/accounts?q=${encodeURIComponent(query)}`;
    return await read(await request(path, signal ? { signal } : undefined), demoAccountsSchema);
  } catch (error) {
    if (error instanceof BackendError && error.status === 404) return null;
    throw error;
  }
}

/** Signs in as a demo account without a password (dev demo only). */
export async function signInAs(userId: string): Promise<CurrentUser> {
  const token = await csrf();
  await request("/dev/demo/sign-in", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
    body: JSON.stringify({ userId }),
  });
  const user = await currentUser();
  if (!user)
    throw new BackendError("The session could not be verified. Please try signing in again.");
  return user;
}

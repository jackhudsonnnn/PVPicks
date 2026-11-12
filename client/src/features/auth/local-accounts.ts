// TODO(backend-middleware): delete when real auth exists; browser password hashing is demo-only.
import {
  HUES,
  normalizeUsername,
  usernameError,
  type Hue,
  type Profile,
} from "@/features/settings/profile";

export { normalizeUsername, USERNAME_MAX, USERNAME_MIN } from "@/features/settings/profile";

/**
 * Accounts people create on the sign-up page. Saved in this browser only until a server exists.
 * Passwords are stored as a salted SHA-256 hash, never as typed.
 * TODO(backend): move sign-up/sign-in to real accounts; drop this module.
 */
export interface LocalAccount {
  username: string;
  hue: Hue;
  salt: string;
  passwordHash: string;
  createdAt: number;
}

export interface SignUpInput {
  username: string;
  password: string;
  confirm: string;
}
export type SignUpErrors = Partial<Record<keyof SignUpInput, string>>;

export const PASSWORD_MIN = 8;

/** Storage key / session id for a signed-up account. */
export const localKey = (username: string) => `user:${normalizeUsername(username)}`;

/** Field errors for the sign-up form; an empty object means the account can be created. */
export function signUpErrors(input: SignUpInput, takenUsernames: string[]): SignUpErrors {
  const e: SignUpErrors = {};
  const u = usernameError(input.username, takenUsernames);
  if (u) e.username = u;

  if (input.password.length < PASSWORD_MIN) e.password = `Use at least ${PASSWORD_MIN} characters.`;
  if (input.confirm !== input.password) e.confirm = "Passwords don't match.";
  return e;
}

async function hashPassword(password: string, salt: string): Promise<string> {
  const bytes = new TextEncoder().encode(`${salt}:${password}`);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Same username always gets the same avatar color. */
function hueFor(username: string): Hue {
  let h = 0;
  for (const c of normalizeUsername(username)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return HUES[h % HUES.length]!;
}

export async function createLocalAccount(
  input: SignUpInput,
  salt: string,
  now: number,
): Promise<LocalAccount> {
  const username = normalizeUsername(input.username);
  return {
    username,
    hue: hueFor(username),
    salt,
    passwordHash: await hashPassword(input.password, salt),
    createdAt: now,
  };
}

/** Username ignores case and spaces; the password must match exactly. */
export async function checkLocalLogin(
  accounts: LocalAccount[],
  username: string,
  password: string,
): Promise<LocalAccount | null> {
  const a = accounts.find((x) => x.username === normalizeUsername(username));
  if (!a) return null;
  return (await hashPassword(password, a.salt)) === a.passwordHash ? a : null;
}

export const personaOf = (a: LocalAccount): Profile => ({
  username: a.username,
  hue: a.hue,
});

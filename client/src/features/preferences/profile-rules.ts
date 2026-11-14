/** Account profile rules: what a valid username looks like. */

export type Hue = 1 | 2 | 3 | 4 | 5;
export const HUES: Hue[] = [1, 2, 3, 4, 5];

export interface Profile {
  username: string;
  hue: Hue;
}

export const USERNAME_MIN = 3;
export const USERNAME_MAX = 15;

/** Usernames are compared ignoring case and surrounding spaces. */
export const normalizeUsername = (raw: string) => raw.trim().toLowerCase();

/** Error for a username, or undefined when it can be used. Uniqueness ignores case. */
export function usernameError(raw: string, taken: string[]): string | undefined {
  const u = normalizeUsername(raw);
  if (u.length < USERNAME_MIN) return `Use at least ${USERNAME_MIN} characters.`;
  if (u.length > USERNAME_MAX) return `Use ${USERNAME_MAX} characters or fewer.`;
  if (!/^[a-z0-9_]+$/.test(u)) return "Use only letters, numbers and underscores.";
  if (taken.map(normalizeUsername).includes(u)) return "Someone already has that username.";
  return undefined;
}

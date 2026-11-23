/** The server's password rules (PasswordPolicy), checked early so the form can say what's missing. */
const PASSWORD_MIN = 12;
const PASSWORD_MAX = 64;

/** Why the password can't be used yet, or null. The server also rejects common passwords. */
export function passwordProblem(password: string, username?: string): string | null {
  const length = [...password].length;
  if (length < PASSWORD_MIN) return `Use at least ${PASSWORD_MIN} characters.`;
  if (length > PASSWORD_MAX) return `Use at most ${PASSWORD_MAX} characters.`;
  if (new TextEncoder().encode(password).length > 72)
    return "That password is too long; use fewer special characters.";
  if (username && password.toLowerCase().includes(username.toLowerCase()))
    return "Don't include your username.";
  return null;
}

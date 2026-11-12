import type { Profile } from "@/features/settings/profile";
import { USERS } from "@/features/mock/seed";

/**
 * Sample sign-ins for smoke testing. These live in the app (no real accounts yet).
 * TODO: replace with real sign-in when a backend exists.
 */
export type PlayerAccountId = "newbie" | "intermediate" | "pro";
/** "admin" is the moderator view (dashboards + reports queue), not a player. */
export type AccountId = PlayerAccountId | "admin";

export interface DemoAccount {
  id: AccountId;
  username: string;
  password: string;
  label: string;
  description: string;
  /** Who you are when signed in as this account (each account is its own person). */
  persona: Profile;
}

export const DEMO_ACCOUNTS: DemoAccount[] = [
  {
    id: "newbie",
    username: "newbie",
    password: "newbie123",
    label: "Newbie",
    description: "Brand new: no tables, follows or history.",
    persona: { username: "newbie_nate", hue: 1 },
  },
  {
    id: "intermediate",
    username: "intermediate",
    password: "inter123",
    label: "Intermediate",
    description: "Some tables and past tickets, nothing live.",
    persona: { username: "inter_ivy", hue: 2 },
  },
  {
    id: "pro",
    username: "pro",
    password: "pro123",
    label: "Pro",
    description: "Lots of tables, hundreds of past tickets, live games and busy chats.",
    persona: { username: "pro_pat", hue: 4 },
  },
  {
    id: "admin",
    username: "admin",
    password: "admin123",
    label: "Admin",
    description: "Moderator view: live dashboards of the three players and the reports queue.",
    persona: { username: "admin", hue: 5 },
  },
];

export const PLAYER_ACCOUNTS: PlayerAccountId[] = ["newbie", "intermediate", "pro"];
const demoAccount = (id: AccountId) => DEMO_ACCOUNTS.find((a) => a.id === id)!;
export const personaFor = (id: AccountId): Profile => ({ ...demoAccount(id).persona });

/** Username is case-insensitive and trimmed; the password must match exactly. */
export function checkLogin(username: string, password: string): AccountId | null {
  const u = username.trim().toLowerCase();
  return DEMO_ACCOUNTS.find((a) => a.username === u && a.password === password)?.id ?? null;
}

export const isAccountId = (v: unknown): v is AccountId => DEMO_ACCOUNTS.some((a) => a.id === v);

/** Short label for who sent something (feedback, reports), from the account's storage key. */
export function accountLabel(key: string | undefined): string {
  if (!key) return "Player";
  if (key.startsWith("user:")) return key.slice(5);
  return isAccountId(key) ? `${demoAccount(key).label} demo` : "Player";
}

/** Usernames no sign-up can take: demo logins, their personas and the sample people. */
export const RESERVED_USERNAMES = [
  ...DEMO_ACCOUNTS.flatMap((a) => [a.username, a.persona.username]),
  ...USERS.map((u) => u.username),
];

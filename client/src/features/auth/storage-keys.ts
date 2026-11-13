/**
 * Every browser storage key the app uses, in one place.
 * TODO(backend): everything except `display` moves to the server once accounts exist.
 */

/** Saved per signed-in account, so one account's data never shows up in another. */
export const accountStorageKeys = (account: string) => ({
  profile: `p2picks.profile.${account}`,
  tableInvites: `p2picks.table-invites.${account}`,
  lastRead: `p2picks.lastRead.${account}`,
  blocks: `p2picks.blocks.${account}`,
  debtCap: `p2picks.debt-cap.${account}`,
});

/** Saved once per browser and shared by every account on it. */
export const SHARED_KEYS = {
  /** Who is signed in on this browser. */
  session: "p2picks.account",
  /** Accounts made on the sign-up page (salted hashes, browser-only). */
  localAccounts: "p2picks.local-accounts",
  /** Theme, text size and other display settings stay with the device. */
  display: "p2picks.display",
  /** Reports and feedback act like server tables so the Admin view sees them. */
  reports: "p2picks.reports",
  feedback: "p2picks.feedback",
  /** Admin's simulated freeze/flag/strike/note actions. */
  adminFlags: "p2picks.admin.flags",
} as const;

/** Old keys that every account used to share; cleared on load so nobody inherits them. */
export const LEGACY_SHARED_KEYS = [
  "p2picks.profile",
  "p2picks.table-invites",
  "p2picks:notification-prefs",
  "p2picks:notification-history",
];

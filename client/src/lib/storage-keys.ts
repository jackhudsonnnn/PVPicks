/**
 * Browser storage keys that aren't per-account. Account data lives on the server; retryable mutations key
 * their own entries per user (see `backend-mutation.ts`).
 */
export const SHARED_KEYS = {
  /** Theme, text size and other display settings stay with the device. */
  display: "p2picks.display",
} as const;

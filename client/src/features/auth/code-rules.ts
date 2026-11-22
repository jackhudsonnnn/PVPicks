import { BackendError, backendErrorMessage, isUnauthorized } from "@/lib/api-client";

/** Why a code didn't work, in words for the player. */
export function codeProblem(failure: unknown) {
  if (failure instanceof BackendError && failure.code === "account_frozen")
    return "This account is frozen. Talk to the admin.";
  if (isUnauthorized(failure))
    return "That username and code don't match. Check them with the admin.";
  if (failure instanceof BackendError && failure.status === 429)
    return "Too many wrong codes for this account. Wait a few minutes, or ask the admin to unlock you.";
  return backendErrorMessage(failure);
}

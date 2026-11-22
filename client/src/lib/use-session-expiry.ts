import { useEffect } from "react";
import { isUnauthorized } from "./api-client";

/**
 * Ends the session when any of these query errors is a 401. Every signed-in view that reads data uses this, so an
 * expired session returns to sign-in instead of showing errors. Returns whether one was, so a view can skip its
 * own error message.
 */
export function useExpireOnUnauthorized(errors: unknown[], expire: () => void): boolean {
  const unauthorized = errors.some(isUnauthorized);
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  return unauthorized;
}

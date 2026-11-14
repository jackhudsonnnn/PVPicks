import { BackendError } from "@/lib/api-client";

const BLOCKERS: Record<string, string> = {
  open_bets: "you're in an Active or Pending bet",
  unsettled_points: "a host hasn't settled points from a bet you won or lost",
  open_receipts: "you have receipts that aren't paid or forgiven",
};

/** Why deleting the account failed, in words; null when it wasn't a deletion-specific refusal. */
export function deletionProblem(error: unknown): string | null {
  if (!(error instanceof BackendError)) return null;
  if (error.code === "wrong_password") return "That password doesn't match your account.";
  if (error.code !== "account_not_square") return null;
  const blockers = (error.body as { blockers?: unknown } | undefined)?.blockers;
  const reasons = Array.isArray(blockers)
    ? blockers.map((blocker) => BLOCKERS[String(blocker)]).filter(Boolean)
    : [];
  return reasons.length
    ? `Settle up first: ${reasons.join("; ")}.`
    : "Settle up before deleting your account.";
}

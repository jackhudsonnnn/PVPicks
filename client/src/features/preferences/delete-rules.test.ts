import { describe, expect, it } from "vitest";
import { BackendError } from "@/lib/api-client";
import { deletionProblem } from "./delete-rules";

describe("account deletion problems", () => {
  it("lists what blocks deleting, in words", () => {
    const error = new BackendError("x", 409, "account_not_square", "Settle up", {
      code: "account_not_square",
      blockers: ["open_bets", "open_receipts"],
    });
    expect(deletionProblem(error)).toBe(
      "Settle up first: you're in an Active or Pending bet; you have receipts that aren't paid or forgiven.",
    );
  });

  it("explains a wrong password and leaves other errors to the generic message", () => {
    expect(deletionProblem(new BackendError("x", 403, "wrong_password"))).toMatch(/password/);
    expect(deletionProblem(new BackendError("x", 503))).toBeNull();
    expect(deletionProblem(new TypeError("offline"))).toBeNull();
  });
});

import { describe, expect, it } from "vitest";
import { isBlocked, queueOrder, reportProblem, setBlock, type Report } from "./safety";

describe("blocking", () => {
  it("blocks one way but counts for both people", () => {
    const b = setBlock({}, "me", "x", true);
    expect(b["me"]).toEqual(["x"]);
    expect(isBlocked(b, "me", "x")).toBe(true);
    expect(isBlocked(b, "x", "me")).toBe(true);
  });

  it("unblocking removes it, and you can't block yourself", () => {
    const b = setBlock(setBlock({}, "me", "x", true), "me", "x", false);
    expect(isBlocked(b, "me", "x")).toBe(false);
    expect(setBlock({}, "me", "me", true)).toEqual({});
  });
});

describe("reports", () => {
  const base = { reporterId: "me", targetId: "x", reason: "spam" as const, note: "" };

  it("needs a reason, and a note when the reason is Other", () => {
    expect(reportProblem(base)).toBeNull();
    expect(reportProblem({ ...base, reason: null })).toBe("Pick a reason.");
    expect(reportProblem({ ...base, reason: "other" })).toBe("Tell us what happened.");
  });

  it("can't report yourself or write more than 500 characters", () => {
    expect(reportProblem({ ...base, targetId: "me" })).toBe("You can't report yourself.");
    expect(reportProblem({ ...base, note: "a".repeat(501) })).not.toBeNull();
    expect(reportProblem({ ...base, note: "a".repeat(500) })).toBeNull();
  });

  it("review queue shows unhandled reports first, newest first", () => {
    const r = (id: string, status: Report["status"], createdAt: number): Report => ({
      id,
      status,
      createdAt,
      reporterId: "a",
      targetId: "b",
      reason: "spam",
      note: "",
    });
    const order = queueOrder([
      r("done", "actioned", 9),
      r("old", "open", 1),
      r("new", "open", 5),
      r("rev", "reviewing", 8),
    ]);
    expect(order.map((x) => x.id)).toEqual(["new", "old", "rev", "done"]);
  });
});

import { describe, expect, it } from "vitest";
import { feedbackProblem, FEEDBACK_KINDS } from "./feedback";

describe("feedback", () => {
  it("offers game mode, perk, GIF, bug and other", () => {
    expect(FEEDBACK_KINDS.map((k) => k.id)).toEqual(["mode", "perk", "gif", "bug", "other"]);
  });

  it("needs 10 to 1000 characters", () => {
    expect(feedbackProblem("too short")).not.toBeNull();
    expect(feedbackProblem("long enough text")).toBeNull();
    expect(feedbackProblem("a".repeat(1001))).not.toBeNull();
  });

  it("an optional link must be a web address", () => {
    expect(feedbackProblem("a good gif idea", "https://tenor.com/x")).toBeNull();
    expect(feedbackProblem("a good gif idea", "tenor dot com")).not.toBeNull();
  });
});

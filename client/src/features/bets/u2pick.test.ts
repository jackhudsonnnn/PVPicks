import { describe, expect, it } from "vitest";
import { cleanOptions, customBetProblem } from "./u2pick";
import { PASS } from "./types";

describe("custom bet options", () => {
  it("trims, drops blanks and duplicates", () => {
    expect(cleanOptions([" Yes ", "", "no", "NO", "Maybe"])).toEqual(["Yes", "no", "Maybe"]);
  });

  it("needs a question and at least two different options", () => {
    expect(customBetProblem("", ["Yes", "No"])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", ""])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", "yes"])).not.toBeNull();
    expect(customBetProblem("Spill?", ["Yes", "No"])).toBeNull();
  });

  it("allows sports pass choices but reserves the no-guess state", () => {
    expect(customBetProblem("Next play?", ["Pass", "Run"])).toBeNull();
    expect(customBetProblem("Q?", ["Yes", "no guess"])).not.toBeNull();
    expect(customBetProblem("Q?", ["Yes", PASS])).not.toBeNull();
  });

  it("allows at most 6 options", () => {
    expect(customBetProblem("Q?", ["1", "2", "3", "4", "5", "6"])).toBeNull();
    expect(customBetProblem("Q?", ["1", "2", "3", "4", "5", "6", "7"])).not.toBeNull();
  });

  it("counts Unicode code points at the question and option limits", () => {
    expect(customBetProblem("🎉".repeat(120), ["🎉".repeat(40), "No"])).toBeNull();
    expect(customBetProblem("🎉".repeat(121), ["Yes", "No"])).not.toBeNull();
    expect(customBetProblem("Q?", ["🎉".repeat(41), "No"])).not.toBeNull();
  });
});

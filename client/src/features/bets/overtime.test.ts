import { describe, expect, it } from "vitest";
import { overtimeCounts, overtimeFor } from "./overtime";

describe("overtime option", () => {
  it("stat and score modes offer it, on by default", () => {
    for (const m of [
      "either_or",
      "king_of_the_hill",
      "scorcerer",
      "total_disaster",
      "give_and_take",
      "prop_hunt",
    ] as const)
      expect(overtimeCounts({ modeId: m })).toBe(true);
  });
  it("single-play and custom modes don't", () => {
    expect(overtimeCounts({ modeId: "choose_their_fate" })).toBeUndefined();
    expect(overtimeCounts({ modeId: "u2pick" })).toBeUndefined();
    expect(overtimeFor("u2pick", false)).toBeUndefined();
  });
  it("keeps an off choice", () => {
    expect(overtimeCounts({ modeId: "prop_hunt", overtime: false })).toBe(false);
  });
});

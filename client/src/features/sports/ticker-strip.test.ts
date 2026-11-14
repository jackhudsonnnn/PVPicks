import { describe, expect, it } from "vitest";
import { repeatForTicker } from "./ticker";

describe("ticker buffer", () => {
  it("handles a feed with no games", () => {
    expect(repeatForTicker([])).toEqual([]);
  });

  it("fills at least 16 slots even with only one game", () => {
    expect(repeatForTicker(["a"])).toEqual(Array(16).fill("a"));
  });

  it("preserves whole cycles at the loop seam", () => {
    const strip = repeatForTicker(["a", "b", "c"]);
    expect(strip).toHaveLength(18);
    for (let index = 0; index < strip.length; index++)
      expect(strip[index]).toBe(["a", "b", "c"][index % 3]);
  });
});

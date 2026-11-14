import { describe, expect, it } from "vitest";
import { summarize } from "./reactions";

describe("summarize", () => {
  it("orders chips by count and flags my own", () => {
    const s = summarize({ "👍": ["a"], "🔥": ["a", "me"] }, "me");
    expect(s.map((x) => [x.emoji, x.count, x.mine])).toEqual([
      ["🔥", 2, true],
      ["👍", 1, false],
    ]);
  });
});

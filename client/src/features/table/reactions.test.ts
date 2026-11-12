import { describe, expect, it } from "vitest";
import { summarize, toggleReaction } from "./reactions";

describe("toggleReaction", () => {
  it("adds a reaction, and tapping the same emoji again removes it", () => {
    const once = toggleReaction({}, "m1", "🔥", "me");
    expect(once["m1"]).toEqual({ "🔥": ["me"] });
    const twice = toggleReaction(once, "m1", "🔥", "me");
    expect(twice["m1"]).toEqual({});
  });

  it("lets several people stack the same emoji", () => {
    let r = toggleReaction({}, "m1", "😂", "a");
    r = toggleReaction(r, "m1", "😂", "b");
    expect(r["m1"]!["😂"]).toEqual(["a", "b"]);
  });

  it("allows only one emoji per person: a new emoji replaces the old one", () => {
    let r = toggleReaction({}, "m1", "😂", "me");
    r = toggleReaction(r, "m1", "💰", "me");
    expect(r["m1"]).toEqual({ "💰": ["me"] });
  });

  it("switching emoji leaves other people's reactions alone", () => {
    let r = toggleReaction({}, "m1", "😂", "a");
    r = toggleReaction(r, "m1", "😂", "me");
    r = toggleReaction(r, "m1", "🔥", "me");
    expect(r["m1"]).toEqual({ "😂": ["a"], "🔥": ["me"] });
  });
});

describe("summarize", () => {
  it("orders chips by count and flags my own", () => {
    const s = summarize({ "👍": ["a"], "🔥": ["a", "me"] }, "me");
    expect(s.map((x) => [x.emoji, x.count, x.mine])).toEqual([
      ["🔥", 2, true],
      ["👍", 1, false],
    ]);
  });
});

import { describe, expect, it } from "vitest";
import { holdFeed } from "./held-feed";

const item = (id: string, v = 0) => ({ id, v });

describe("held feed", () => {
  it("is live with nothing frozen", () => {
    const items = [item("a"), item("b")];
    expect(holdFeed(items, null, new Map())).toEqual({ shown: items, held: 0 });
  });

  it("holds new items back above, keeps the shown ones in place with fresh data, and appends the next page", () => {
    const known = new Map([["c", item("c", 1)]]);
    // "n" is new at the top; "b" updated; "c" left the feed; "d" is the next page.
    const items = [item("n"), item("a"), item("b", 2), item("d")];
    const { shown, held } = holdFeed(items, ["a", "b", "c"], known);
    expect(shown).toEqual([item("a"), item("b", 2), item("c", 1), item("d")]);
    expect(held).toBe(1);
  });
});

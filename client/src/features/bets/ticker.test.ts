import { describe, expect, it } from "vitest";
import { GAMES } from "@/features/mock/seed";
import { tickerWindow } from "./ticker";

describe("ticker buffer", () => {
  it("handles a feed with no games", () => {
    expect(tickerWindow([])).toEqual([]);
  });

  it("fills at least 16 slots even with only one game", () => {
    const game = GAMES[0];
    if (!game) throw new Error("Test requires a sample game");
    expect(tickerWindow([game])).toEqual(Array(16).fill(game));
  });

  it("preserves whole modulo cycles at the loop seam", () => {
    const source = GAMES.slice(0, 3);
    expect(source).toHaveLength(3);
    const window = tickerWindow(source);
    expect(window).toHaveLength(18);
    expect(window.slice(-3)).toEqual(window.slice(0, 3));
    for (let index = 3; index < window.length; index++) {
      expect(window[index]).toBe(window[index % 3]);
    }
  });

  it("prioritizes live games without changing the feed", () => {
    const game = GAMES[0];
    if (!game) throw new Error("Test requires a sample game");
    const upcoming = { ...game, id: "upcoming", live: false };
    const live = { ...game, id: "live", live: true };
    const source = [upcoming, live];
    expect(tickerWindow(source).slice(0, 2)).toEqual([live, upcoming]);
    expect(source).toEqual([upcoming, live]);
  });

  it("keeps every game in a feed larger than the minimum buffer", () => {
    const game = GAMES[0];
    if (!game) throw new Error("Test requires a sample game");
    const source = Array.from({ length: 23 }, (_, index) => ({ ...game, id: `game-${index}` }));
    expect(tickerWindow(source)).toEqual(source);
  });
});
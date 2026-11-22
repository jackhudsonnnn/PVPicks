import { describe, expect, it } from "vitest";
import type { GamePlayer } from "./api";
import { byPosition } from "./roster-order";

const player = (name: string, position: string | null, team = "BOS"): GamePlayer => ({
  id: name,
  name,
  position,
  team,
  played: true,
  stats: {},
});

describe("byPosition", () => {
  it("orders NFL players by position, then team and name", () => {
    const sorted = byPosition([
      player("Kicker", "K"),
      player("Zed Receiver", "WR", "NYG"),
      player("Amy Receiver", "WR", "NYG"),
      player("Quarterback", "QB"),
      player("Linebacker", "LB"),
      player("Back", "RB"),
      player("Early Receiver", "WR", "DAL"),
    ]);
    expect(sorted.map((p) => p.name)).toEqual([
      "Quarterback",
      "Back",
      "Early Receiver",
      "Amy Receiver",
      "Zed Receiver",
      "Kicker",
      "Linebacker",
    ]);
  });

  it("orders NBA players guards to centers, with unknown and missing positions last", () => {
    const sorted = byPosition([
      player("Center", "C"),
      player("Nobody", null),
      player("Forward", "F"),
      player("Swing", "G-F"),
      player("Mystery", "XX"),
      player("Guard", "G"),
    ]);
    expect(sorted.map((p) => p.name)).toEqual([
      "Guard",
      "Swing",
      "Forward",
      "Center",
      "Mystery",
      "Nobody",
    ]);
  });
});

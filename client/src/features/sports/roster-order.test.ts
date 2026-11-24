import { describe, expect, it } from "vitest";
import type { GamePlayer } from "./api";
import { byPosition, forStat } from "./roster-order";

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

describe("forStat", () => {
  const withStat = (name: string, position: string, value: number, stat = "passing_yards") => ({
    ...player(name, position),
    stats: { [stat]: value },
  });

  it("leads with the stat's positions in order, each by value, and puts everyone else after", () => {
    const { main, others } = forStat(
      [
        withStat("Backup QB", "QB", 0),
        withStat("Josh Allen", "QB", 212),
        withStat("Drake Maye", "QB", 248),
        withStat("Trick WR", "WR", 20),
        withStat("Kicker", "K", 0),
        withStat("Halfback", "RB", 0),
      ],
      "passing_yards",
    );
    expect(main.map((p) => p.name)).toEqual([
      "Drake Maye",
      "Josh Allen",
      "Backup QB",
      "Halfback",
      "Trick WR",
    ]);
    expect(others.map((p) => p.name)).toEqual(["Kicker"]);
  });

  it("puts running backs first for rushing yards, then quarterbacks", () => {
    const { main } = forStat(
      [
        withStat("QB", "QB", 40, "rushing_yards"),
        withStat("RB2", "RB", 12, "rushing_yards"),
        withStat("RB1", "HB", 70, "rushing_yards"),
        withStat("WR", "WR", 9, "rushing_yards"),
      ],
      "rushing_yards",
    );
    expect(main.map((p) => p.name)).toEqual(["RB1", "RB2", "QB", "WR"]);
  });

  it("orders NBA stats by value alone", () => {
    const { main, others } = forStat(
      [withStat("C", "C", 8, "points"), withStat("PG", "PG", 31, "points")],
      "points",
    );
    expect(main.map((p) => p.name)).toEqual(["PG", "C"]);
    expect(others).toEqual([]);
  });

  it("ranks by season average when everyone is on 0 before kickoff", () => {
    const { main } = forStat(
      [
        { ...withStat("Backup", "QB", 0), averages: { passing_yards: 88.1 } },
        { ...withStat("Josh Allen", "QB", 0), averages: { passing_yards: 245.3 } },
        withStat("No average", "QB", 0),
      ],
      "passing_yards",
    );
    expect(main.map((p) => p.name)).toEqual(["Josh Allen", "Backup", "No average"]);
  });
});

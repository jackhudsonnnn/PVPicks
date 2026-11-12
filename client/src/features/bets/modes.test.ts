import { describe, expect, it } from "vitest";
import { GAMES } from "@/features/mock/seed";
import { MODES, getMode, modeProblem, statsFor } from "./modes";

const nfl = GAMES.find((g) => g.league === "nfl")!;
const nba = GAMES.find((g) => g.league === "nba")!;

describe("modes across leagues", () => {
  it("NBA games use basketball stats, NFL games use football stats", () => {
    expect(statsFor(nba)).toContain("Points");
    expect(statsFor(nba)).not.toContain("Passing Yards");
    expect(statsFor(nfl)).toContain("Passing Yards");
  });

  it("Choose Their Fate asks about a drive in the NFL and a possession in the NBA", () => {
    const m = getMode("choose_their_fate");
    expect(m.question(m.defaults(nfl), nfl)).toMatch(/drive/);
    expect(m.question(m.defaults(nba), nba)).toMatch(/possession/);
    expect(m.choices({}, nba)).toContain("Three-Pointer");
  });

  it("U2Pick uses the proposer's own question and options", () => {
    const m = getMode("u2pick");
    const c = { question: " Jack to spill beer? ", options: ["Yes", "No"] };
    expect(m.question(c, nfl)).toBe("Jack to spill beer?");
    expect(m.choices(c, nfl)).toEqual(["Yes", "No"]);
  });
});

describe("mode audit", () => {
  const gameModes = MODES.filter((m) => !m.custom);

  it("every mode gives every game at least two different choices and a question", () => {
    for (const g of GAMES)
      for (const m of gameModes) {
        const c = m.defaults(g);
        const choices = m.choices(c, g);
        expect(new Set(choices).size).toBe(choices.length);
        expect(choices.length).toBeGreaterThanOrEqual(2);
        expect(m.question(c, g).length).toBeGreaterThan(0);
        expect(modeProblem(m.id, c)).toBeNull();
      }
  });

  it("the spread choices are mirror images", () => {
    const m = getMode("give_and_take");
    expect(m.choices({ spread: -3.5 }, nfl)).toEqual([
      `${nfl.home.abbr} -3.5`,
      `${nfl.away.abbr} +3.5`,
    ]);
  });

  it("Either Or and King Of The Hill need two different players", () => {
    expect(modeProblem("either_or", { playerA: "p1", playerB: "p1" })).toMatch(/different/);
    expect(modeProblem("king_of_the_hill", { playerA: "p1", playerB: "p2", target: 0 })).toMatch(
      /above 0/,
    );
  });
});

import { describe, expect, it } from "vitest";
import {
  suggestedLine,
  playerNames,
  playerProblem,
  playerTerms,
  proposableGames,
  scoreLine,
  scoreTerms,
  startingLine,
  halfLine,
  stepLine,
} from "./sports-model";
import type { TickerGame } from "@/features/sports/api";

const game = (id: string, status: TickerGame["status"], startsAt: string): TickerGame => ({
  id,
  league: "NBA",
  dev: false,
  status,
  startsAt,
  home: { abbreviation: "BOS", score: status === "SCHEDULED" ? null : 101 },
  away: { abbreviation: "NYK", score: status === "SCHEDULED" ? null : 98 },
  observedAt: startsAt,
});

describe("score modes", () => {
  it("builds the same question and choices as the server", () => {
    expect(scoreTerms("total_disaster", 224.5, "BOS", "NYK")).toEqual({
      question: "NYK + BOS combined: over or under 224.5?",
      choices: ["Over 224.5", "Under 224.5"],
    });
    expect(scoreTerms("give_and_take", -5.5, "BOS", "NYK").choices).toEqual([
      "BOS -5.5",
      "NYK +5.5",
    ]);
    expect(scoreTerms("give_and_take", 3, "BOS", "NYK").choices).toEqual(["BOS +3", "NYK -3"]);
    expect(scoreTerms("give_and_take", 0, "BOS", "NYK").choices).toEqual(["BOS 0", "NYK 0"]);
  });

  it("keeps lines on a .5 within limits, so they never push", () => {
    expect(stepLine("total_disaster", 224.5, 1)).toBe(225.5);
    expect(stepLine("total_disaster", 225, 0)).toBe(225.5);
    expect(stepLine("total_disaster", 0.5, -1)).toBe(0.5);
    expect(stepLine("give_and_take", -5.5, 1)).toBe(-4.5);
    expect(stepLine("give_and_take", -0.5, 1)).toBe(0.5);
    expect(stepLine("give_and_take", -99.5, -1)).toBe(-99.5);
    expect(halfLine(24, 0.5, 999.5)).toBe(24.5);
  });

  it("offers only games still taking bets, live first", () => {
    const games = [
      game("NBA-3", "SCHEDULED", "2026-11-14T03:00:00Z"),
      game("NBA-1", "FINAL", "2026-11-14T00:00:00Z"),
      game("NBA-2", "LIVE", "2026-11-14T01:00:00Z"),
      game("NBA-4", "SCHEDULED", "2026-11-14T02:00:00Z"),
    ];
    expect(proposableGames(games).map((g) => g.id)).toEqual(["NBA-2", "NBA-4", "NBA-3"]);
    expect(proposableGames(null)).toEqual([]);
    expect(scoreLine(games[2]!, "Q4 1:22")).toBe("NYK 98 @ BOS 101 · Q4 1:22");
  });
});

describe("player modes", () => {
  const ann = { name: "Ann Archer", team: "BOS" };
  const ben = { name: "Ben Baker", team: "NYK" };

  it("builds the questions and choices the server builds", () => {
    expect(playerTerms("either_or", "Points", [ann, ben], 0, "BOS", "NYK")).toEqual({
      question: "Who gets more points: Ann Archer or Ben Baker?",
      choices: ["Ann Archer", "Ben Baker"],
    });
    expect(playerTerms("prop_hunt", "Points", [ann], 24.5, "BOS", "NYK")).toEqual({
      question: "Ann Archer points: over or under 24.5?",
      choices: ["Over 24.5", "Under 24.5"],
    });
    expect(playerTerms("king_of_the_hill", "Points", [ann, ben], 30, "BOS", "NYK").choices).toEqual(
      ["Ann Archer", "Ben Baker", "Neither"],
    );
    expect(playerTerms("scorcerer", "", [], 0, "DET", "GB", "NFL")).toEqual({
      question: "GB @ DET: next touchdown or field goal?",
      choices: [
        "GB touchdown",
        "GB field goal",
        "DET touchdown",
        "DET field goal",
        "No more scores",
      ],
    });
    expect(playerTerms("scorcerer", "", [], 0, "BOS", "NYK", "NBA")).toEqual({
      question: "NYK @ BOS: next basket?",
      choices: ["NYK 3", "NYK 2", "BOS 3", "BOS 2", "No more scores"],
    });
    expect(playerNames([ann, { ...ann, team: "NYK" }])).toEqual([
      "Ann Archer (BOS)",
      "Ann Archer (NYK)",
    ]);
  });

  it("starts lines above the players and explains what's missing", () => {
    expect(startingLine("prop_hunt", [20])).toBe(20.5);
    expect(startingLine("king_of_the_hill", [20, 27])).toBe(28);
    expect(playerProblem("either_or", [{ stat: 1 }], 0)).toBe("Pick two players.");
    expect(playerProblem("prop_hunt", [{ stat: 25 }], 24.5)).toMatch(/above the player/);
    expect(playerProblem("king_of_the_hill", [{ stat: 10 }, { stat: 30 }], 30)).toMatch(/both/);
    expect(playerProblem("prop_hunt", [{ stat: 20 }], 24.5)).toBeNull();
  });
});

describe("suggested lines", () => {
  it("starts at the season average before kickoff, never below what's happened", () => {
    expect(suggestedLine("prop_hunt", [{ current: 0, average: 245.3 }])).toBe(245.5);
    // Past their average already: just above what they have.
    expect(suggestedLine("prop_hunt", [{ current: 260, average: 245.3 }])).toBe(260.5);
    expect(suggestedLine("prop_hunt", [{ current: 12, average: undefined }])).toBe(12.5);
    expect(
      suggestedLine("king_of_the_hill", [
        { current: 0, average: 71.6 },
        { current: 0, average: 58 },
      ]),
    ).toBe(72);
  });
});

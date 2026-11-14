import { describe, expect, it } from "vitest";
import { canPush, proposableGames, scoreLine, scoreTerms, stepLine } from "./sports-model";
import type { TickerGame } from "@/features/sports/api";

const game = (id: string, status: TickerGame["status"], startsAt: string): TickerGame => ({
  id,
  league: "NBA",
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

  it("steps lines by half points within limits", () => {
    expect(stepLine("total_disaster", 224.5, 0.5)).toBe(225);
    expect(stepLine("total_disaster", 0.5, -1)).toBe(0.5);
    expect(stepLine("give_and_take", -99.5, -0.5)).toBe(-99.5);
    expect(canPush(225)).toBe(true);
    expect(canPush(224.5)).toBe(false);
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

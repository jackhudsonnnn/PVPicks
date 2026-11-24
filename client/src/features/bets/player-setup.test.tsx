import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { GamePlayers } from "@/features/sports";
import { PlayerSetup } from "./components/player-setup";

const roster: GamePlayers = {
  stats: [{ key: "passing_yards", label: "Passing Yards" }],
  players: [
    {
      id: "allen",
      name: "Josh Allen",
      position: "QB",
      team: "BUF",
      played: true,
      stats: { passing_yards: 250 },
    },
  ],
};

describe("player setup", () => {
  it("never offers a line the player has already passed, and says where they are now", () => {
    const onLine = vi.fn();
    render(
      <PlayerSetup
        mode="prop_hunt"
        roster={roster}
        stat="passing_yards"
        onStat={() => {}}
        playerIds={["allen"]}
        onPlayers={() => {}}
        line={200.5}
        onLine={onLine}
      />,
    );
    // 200.5 would wash at lock, so it moves to just above his 250.
    expect(onLine).toHaveBeenCalledWith(250.5);
    expect(screen.getByText(/Now: Josh Allen 250 passing yards/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /minimum, 250\.5/ })).toBeInTheDocument();
  });
});

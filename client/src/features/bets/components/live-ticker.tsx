import { memo, useMemo } from "react";
import { useGames } from "@/features/store";
import { SPORTS } from "../sports";
import type { Game } from "../types";
import { tickerWindow } from "../ticker";

/**
 * Stock-ticker strip of every game. Slides on its own (pauses on hover/touch);
 * with Reduced motion it stays still and you swipe it instead.
 */
export const LiveTicker = memo(function LiveTicker() {
  const source = useGames();
  const games = useMemo(() => tickerWindow(source), [source]);
  return (
    <section aria-label="Live games" className="h-10 border-b bg-sidebar">
      <div className="ticker h-full" tabIndex={0}>
        <div className="ticker-track flex h-full w-max items-center" style={{ animationDuration: `${games.length * 11}s` }}>
          <TickerItems games={games} sourceCount={source.length} />
          <div aria-hidden className="ticker-dup flex h-full shrink-0 items-center">
            <TickerItems games={games} sourceCount={source.length} />
          </div>
        </div>
      </div>
    </section>
  );
});

function TickerItems({ games, sourceCount }: { games: Game[]; sourceCount: number }) {
  return (
    <>
      {games.map((g, index) => (
        <div
          key={`${g.id}:${index}`}
          data-ticker-repeat={index >= sourceCount ? "true" : undefined}
          aria-hidden={index >= sourceCount || undefined}
          className="flex h-full w-[22rem] shrink-0 items-center gap-2.5 border-r px-4 font-mono text-xs"
        >
          <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-bold">
            {SPORTS[g.league].label}
          </span>
          <span className="scoreboard text-base">
            {g.away.abbr} {g.live ? g.awayScore : ""}
          </span>
          <span className="text-muted-foreground">@</span>
          <span className="scoreboard text-base">
            {g.home.abbr} {g.live ? g.homeScore : ""}
          </span>
          <span className="ml-auto flex min-w-0 items-center gap-1.5 truncate text-muted-foreground">
            {g.live && <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" />}
            {g.live ? g.clock : `Upcoming · ${g.clock}`}
          </span>
        </div>
      ))}
    </>
  );
}

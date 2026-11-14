import { useEffect, useMemo } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { repeatForTicker } from "@/features/bets/ticker";
import { BackendError, type BackendUser } from "./backend-api";
import { useRealtimeFeed } from "./realtime";
import { tickerFeed } from "./realtime-connection";
import { getTicker, tickerStatus, type TickerGame } from "./ticker-api";

/**
 * Sports-only score strip. Refreshes when the realtime ticker feed says scores changed and once a minute as a
 * fallback. Slides on its own (pauses on hover/focus); with reduced motion it stays still and scrolls instead.
 */
export function BackendTicker({ user, expire }: { user: BackendUser; expire: () => void }) {
  const queryClient = useQueryClient();
  const key = ["backend-tables", user.id, "ticker"];
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getTicker(signal),
    retry: false,
    refetchInterval: 60_000,
  });
  useRealtimeFeed(tickerFeed, () => void queryClient.invalidateQueries({ queryKey: key }));
  const unauthorized = query.error instanceof BackendError && query.error.status === 401;
  useEffect(() => {
    if (unauthorized) expire();
  }, [unauthorized, expire]);
  const games = useMemo(() => repeatForTicker(query.data ?? []), [query.data]);
  const count = query.data?.length ?? 0;

  let notice: string | null = null;
  if (query.isPending) notice = "Loading scores…";
  else if (query.isError || query.data === null) notice = "Live scores are unavailable right now.";
  else if (count === 0) notice = "No NBA or NFL games today.";

  return (
    <section aria-label="Live games" className="h-10 border-b bg-sidebar">
      {notice ? (
        <p className="flex h-full items-center px-4 font-mono text-xs text-muted-foreground">
          {notice}
        </p>
      ) : (
        <div className="ticker h-full" tabIndex={0}>
          <div
            className="ticker-track flex h-full w-max items-center"
            style={{ animationDuration: `${games.length * 11}s` }}
          >
            <TickerItems games={games} sourceCount={count} />
            <div aria-hidden className="ticker-dup flex h-full shrink-0 items-center">
              <TickerItems games={games} sourceCount={count} />
            </div>
          </div>
        </div>
      )}
    </section>
  );
}

function TickerItems({ games, sourceCount }: { games: TickerGame[]; sourceCount: number }) {
  return (
    <>
      {games.map((game, index) => {
        const started = game.status === "LIVE" || game.status === "FINAL";
        return (
          <div
            key={`${game.id}:${index}`}
            data-ticker-repeat={index >= sourceCount ? "true" : undefined}
            aria-hidden={index >= sourceCount || undefined}
            className="flex h-full w-auto shrink-0 items-center gap-2.5 border-r px-4 font-mono text-xs md:w-[22rem]"
          >
            <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-bold">
              {game.league}
            </span>
            <span className="scoreboard text-base">
              {game.away.abbreviation} {started ? (game.away.score ?? "") : ""}
            </span>
            <span className="text-muted-foreground">@</span>
            <span className="scoreboard text-base">
              {game.home.abbreviation} {started ? (game.home.score ?? "") : ""}
            </span>
            <span className="ml-2 flex min-w-0 items-center gap-1.5 truncate text-muted-foreground md:ml-auto">
              {game.status === "LIVE" && (
                <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" />
              )}
              {game.status === "SCHEDULED"
                ? `Upcoming · ${tickerStatus(game)}`
                : tickerStatus(game)}
            </span>
          </div>
        );
      })}
    </>
  );
}

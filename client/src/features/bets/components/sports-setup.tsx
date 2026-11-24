import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { LoadingList } from "@/components/loading";
import { cn } from "@/lib/utils";
import { tickerStatus, type TickerGame } from "@/features/sports";
import { LINE_LIMITS, stepLine } from "@/features/bets/sports-model";
import type { SportsDraft } from "@/features/bets/use-sports-draft";
import { Section, ValueSlider } from "./form-parts";
import { PlayerSetup } from "./player-setup";

/**
 * The sports part of the proposal sheet: the league and game, then the player setup (stat, players, line or target)
 * or the score line, and whether overtime counts. State lives in {@link useSportsDraft}.
 */
export function SportsSetup({ draft }: { draft: SportsDraft }) {
  const {
    leagues,
    league,
    pickLeague,
    lostGame,
    ticker,
    games,
    gameId,
    pickGame,
    playerMode,
    game,
    roster,
    statKey,
    chooseStat,
    playerIds,
    choosePlayers,
    line,
    setLine,
    scoreMode,
    totalFloor,
    overtime,
    setOvertime,
  } = draft;
  return (
    <>
      <Section label="Game">
        <SegmentedTabs<TickerGame["league"]>
          size="sm"
          ariaLabel="League"
          tabs={leagues.map((l) => ({ id: l, label: l }))}
          value={league}
          onChange={pickLeague}
        />
        {lostGame && (
          <p className="text-sm text-loss">
            That game can't take new bets any more (it's over or postponed). Pick another.
          </p>
        )}
        {ticker.data === null ? (
          <p className="text-sm text-muted-foreground">
            Live scores are unavailable, so games can't be picked right now.
          </p>
        ) : games.filter((g) => g.league === league).length === 0 ? (
          ticker.isPending ? (
            <LoadingList label="Loading games" rows={3} rowClassName="h-12" />
          ) : (
            <p className="text-sm text-muted-foreground">No {league} games left today.</p>
          )
        ) : (
          <div className="grid gap-2" role="radiogroup" aria-label="Game">
            {games
              .filter((g) => g.league === league)
              .map((g) => (
                <button
                  key={g.id}
                  type="button"
                  role="radio"
                  aria-checked={g.id === gameId}
                  onClick={() => pickGame(g)}
                  className={cn(
                    "flex items-center justify-between rounded-lg border px-3 py-2.5 text-sm transition-colors",
                    g.id === gameId
                      ? "border-primary bg-primary/10"
                      : "bg-surface hover:border-muted-foreground",
                  )}
                >
                  <span className="scoreboard text-lg">
                    {g.away.abbreviation} {g.status === "LIVE" && g.away.score}{" "}
                    <span className="text-muted-foreground">@</span> {g.home.abbreviation}{" "}
                    {g.status === "LIVE" && g.home.score}
                  </span>
                  <span className="flex items-center gap-2 font-mono text-[11px] text-muted-foreground">
                    {g.status === "LIVE" && (
                      <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" />
                    )}
                    {g.dev && (
                      <span className="rounded bg-status-pending/20 px-1 font-bold text-status-pending">
                        DEV
                      </span>
                    )}
                    {tickerStatus(g)}
                  </span>
                </button>
              ))}
          </div>
        )}
      </Section>
      {playerMode && game && (
        <PlayerSetup
          mode={playerMode}
          roster={roster.data}
          stat={statKey}
          onStat={chooseStat}
          playerIds={playerIds}
          onPlayers={(ids) => choosePlayers(ids)}
          line={line}
          onLine={setLine}
        />
      )}
      {scoreMode && (
        <Section label="Setup">
          <div className="grid gap-1.5 text-xs font-medium text-muted-foreground">
            {scoreMode === "total_disaster"
              ? "Total points line"
              : `${game?.home.abbreviation ?? "Home"} spread`}
            <ValueSlider
              value={line}
              // Lines end in .5 (never a push), so they move a whole point at a time.
              step={1}
              min={
                scoreMode === "total_disaster" && totalFloor !== null
                  ? totalFloor
                  : LINE_LIMITS[scoreMode].min
              }
              max={LINE_LIMITS[scoreMode].max}
              label={scoreMode === "total_disaster" ? "Total points line" : "Home team spread"}
              format={(v) => (scoreMode === "give_and_take" && v > 0 ? `+${v}` : String(v))}
              // Totals run into the hundreds; 399.5 covers any game. Spreads stay under 100.
              sliderMax={scoreMode === "total_disaster" ? 399.5 : undefined}
              onChange={(v) =>
                setLine(Math.max(totalFloor ?? -Infinity, stepLine(scoreMode, v, 0)))
              }
            />
            {totalFloor !== null && (
              <span className="font-normal">
                Now: {totalFloor - 0.5} combined. The line starts above that.
              </span>
            )}
          </div>
        </Section>
      )}
      {(scoreMode || (playerMode && game)) && (
        <div className="flex items-center justify-between gap-4 rounded-xl border bg-surface p-3">
          <span className="min-w-0">
            <span className="block text-sm font-semibold">Overtime counts</span>
            <span className="block text-xs text-muted-foreground">
              {overtime
                ? scoreMode
                  ? "Points scored in overtime count."
                  : "Stats in overtime count."
                : "Decided at the end of regulation."}
            </span>
          </span>
          <Switch checked={overtime} onCheckedChange={setOvertime} aria-label="Overtime counts" />
        </div>
      )}
    </>
  );
}

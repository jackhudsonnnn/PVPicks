import { type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { LoadingList } from "@/components/loading";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import {
  LEAGUES,
  listSportsDays,
  listSportsGames,
  listSportsLeagues,
  type League,
} from "./dashboard-api";
import { ago } from "./format";
import { GameInspector, type SportsPanel } from "./sports-inspector";
import { Hint, StatusChip } from "./sports-parts";
import { LIVE_MS, periodName } from "./format";

export type { SportsPanel };

export interface SportsSelection {
  league: League | null;
  day: string | null;
  game: string | null;
  panel: SportsPanel;
  player: string | null;
}

/** Today's date in New York, the calendar the poller files games under. */
const today = () =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "America/New_York" }).format(new Date());

/**
 * The sports explorer: Finder-style columns (league → New York day → game) and an inspector for the chosen game
 * with Score, Timeline (every stored snapshot, scrubbable), Players (current stats; a player's stat history) and
 * the raw JSON as a tree. Live games refresh every 5 seconds and briefly highlight what changed. On a phone the
 * columns become a drill-down with a breadcrumb. The URL holds the selection.
 */
export function SportsTab({
  user,
  selection,
  onSelect,
}: {
  user: User;
  selection: SportsSelection;
  onSelect: (next: SportsSelection, push?: boolean) => void;
}) {
  // The league is a dropdown; with none in the address yet, NBA.
  const league: League = selection.league ?? "NBA";
  const { day, game } = selection;
  const leagues = useQuery({
    queryKey: userKey(user.id, "admin-sports", "leagues"),
    queryFn: ({ signal }) => listSportsLeagues(signal),
    refetchInterval: 30_000,
    retry: false,
  });
  const days = useQuery({
    queryKey: userKey(user.id, "admin-sports", "days", league),
    queryFn: ({ signal }) => listSportsDays(league, signal),
    refetchInterval: 60_000,
    retry: false,
  });
  const games = useQuery({
    queryKey: userKey(user.id, "admin-sports", "games", league, day ?? ""),
    queryFn: ({ signal }) => listSportsGames(league, day!, signal),
    enabled: day !== null,
    refetchInterval: (query) =>
      query.state.data?.some((g) => g.status === "LIVE") ? LIVE_MS : 60_000,
    retry: false,
  });
  const pick = (next: Partial<SportsSelection>, push = true) =>
    onSelect({ ...selection, league, ...next }, push);
  // Mobile shows one level at a time: the deepest one chosen.
  const level = game ? 2 : day ? 1 : 0;
  const current = leagues.data?.find((row) => row.league === league);
  const failing =
    current?.lastFailureAt &&
    (!current.lastSuccessAt || current.lastFailureAt > current.lastSuccessAt);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {/* Feed status over the location, together about the dropdown's height and centered on it. */}
        <div className="flex min-w-0 flex-col justify-center gap-1">
          {leagues.isError ? (
            <p role="alert" className="text-xs">
              {backendErrorMessage(leagues.error)}
            </p>
          ) : (
            current && (
              <p className="flex items-center gap-2 text-[11px] leading-none text-muted-foreground">
                {current.live > 0 && <LiveDot count={current.live} />}
                {failing
                  ? `feed failing: ${current.lastFailure ?? "unknown"}`
                  : `feed updated ${ago(current.lastSuccessAt)}`}
              </p>
            )
          )}
          <Breadcrumb
            selection={{ ...selection, league }}
            onSelect={(next) => onSelect(next, true)}
          />
        </div>
        <Select
          value={league}
          onValueChange={(value) =>
            pick({ league: value as League, day: null, game: null, player: null })
          }
        >
          <SelectTrigger aria-label="League" className="h-9 w-full sm:w-48 bg-surface">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {LEAGUES.map((id) => {
              const row = leagues.data?.find((l) => l.league === id);
              return (
                <SelectItem key={id} value={id}>
                  {id}
                  {row ? ` · ${row.games} games${row.live ? ` · ${row.live} live` : ""}` : ""}
                </SelectItem>
              );
            })}
          </SelectContent>
        </Select>
      </div>
      <div className="grid gap-3 lg:grid-cols-[200px_270px_minmax(0,1fr)]">
        <Column title="Day (New York)" className={cn(level !== 0 && "hidden lg:flex")}>
          {days.isPending ? (
            <LoadingList label="Loading days" rows={6} rowClassName="h-10" />
          ) : days.isError ? (
            <p role="alert" className="p-2 text-sm">
              {backendErrorMessage(days.error)}
            </p>
          ) : days.data.length === 0 ? (
            <Hint>No games stored.</Hint>
          ) : (
            <>
              <Button
                size="sm"
                variant="ghost"
                className="justify-start"
                onClick={() => pick({ day: today(), game: null, player: null })}
              >
                Today · {today()}
              </Button>
              {days.data.map((row) => (
                <Item
                  key={row.date}
                  active={day === row.date}
                  onClick={() => pick({ day: row.date, game: null, player: null })}
                >
                  <span
                    className={cn("block font-mono text-xs", row.date === today() && "font-bold")}
                  >
                    {weekday(row.date)} {row.date}
                  </span>
                  <span className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
                    {row.games} games · {row.finals} final
                    {row.live > 0 && <LiveDot count={row.live} />}
                  </span>
                </Item>
              ))}
            </>
          )}
        </Column>

        <Column title="Game" className={cn(level !== 1 && "hidden lg:flex")}>
          {day === null ? (
            <Hint>Pick a day.</Hint>
          ) : games.isPending ? (
            <LoadingList label="Loading games" rows={5} rowClassName="h-12" />
          ) : games.isError ? (
            <p role="alert" className="p-2 text-sm">
              {backendErrorMessage(games.error)}
            </p>
          ) : games.data.length === 0 ? (
            <Hint>No games that day.</Hint>
          ) : (
            games.data.map((row) => (
              <Item
                key={row.id}
                active={game === row.id}
                onClick={() => pick({ game: row.id, player: null })}
              >
                <span className="flex items-center justify-between gap-2 font-mono text-xs">
                  <span className="font-semibold">
                    {row.away.abbreviation} @ {row.home.abbreviation}
                  </span>
                  <span>
                    {row.away.score ?? "–"}–{row.home.score ?? "–"}
                  </span>
                </span>
                <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                  <StatusChip status={row.status} />
                  {row.status === "LIVE"
                    ? `${periodName(row.league, row.period)} ${row.clock ?? ""}`
                    : new Date(row.startsAt).toLocaleTimeString(undefined, {
                        hour: "numeric",
                        minute: "2-digit",
                      })}
                  {row.bets > 0 && <span>· {row.bets} bets</span>}
                </span>
              </Item>
            ))
          )}
        </Column>

        <div className={cn("min-w-0", level !== 2 && "hidden lg:block")}>
          {game === null ? (
            <div className="flex h-full min-h-48 items-center justify-center rounded-xl border border-dashed p-6 text-sm text-muted-foreground">
              Pick a game to inspect it.
            </div>
          ) : (
            <GameInspector
              key={game}
              user={user}
              gameId={game}
              panel={selection.panel}
              player={selection.player}
              onPanel={(panel) => pick({ panel }, false)}
              onPlayer={(player) => pick({ player, panel: "players" }, false)}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function Breadcrumb({
  selection,
  onSelect,
}: {
  selection: SportsSelection;
  onSelect: (next: SportsSelection) => void;
}) {
  const crumbs: { label: string; next: SportsSelection }[] = [];
  if (selection.league)
    crumbs.push({
      label: selection.league,
      next: { ...selection, day: null, game: null, player: null },
    });
  if (selection.day)
    crumbs.push({ label: selection.day, next: { ...selection, game: null, player: null } });
  if (selection.game) crumbs.push({ label: selection.game, next: selection });
  return (
    <nav
      aria-label="Sports location"
      className="flex flex-wrap items-center gap-1 font-mono text-xs"
    >
      {crumbs.map((crumb, i) => (
        <span key={crumb.label} className="inline-flex items-center gap-1">
          {i > 0 && <ChevronRight className="size-3 text-muted-foreground" />}
          {i === crumbs.length - 1 ? (
            <span className="font-bold">{crumb.label}</span>
          ) : (
            <button
              type="button"
              className="underline-offset-2 hover:underline"
              onClick={() => onSelect(crumb.next)}
            >
              {crumb.label}
            </button>
          )}
        </span>
      ))}
    </nav>
  );
}

function Column({
  title,
  className,
  children,
}: {
  title: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section
      aria-label={title}
      className={cn(
        "flex max-h-[75vh] min-h-48 flex-col gap-0.5 overflow-y-auto rounded-xl border bg-surface/60 p-1.5",
        className,
      )}
    >
      <h3 className="px-2 py-1 font-mono text-[10px] font-bold uppercase tracking-[0.2em] text-muted-foreground">
        {title}
      </h3>
      {children}
    </section>
  );
}

function Item({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-current={active ? "true" : undefined}
      onClick={onClick}
      className={cn(
        "w-full rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted/60",
        active && "bg-primary/15 hover:bg-primary/20",
      )}
    >
      {children}
    </button>
  );
}

function LiveDot({ count }: { count: number }) {
  return (
    <span className="inline-flex items-center gap-1 font-mono text-[10px] font-bold text-loss">
      <span className="size-1.5 animate-pulse-dot rounded-full bg-loss" /> {count} live
    </span>
  );
}

function weekday(date: string) {
  return new Date(`${date}T12:00:00Z`).toLocaleDateString(undefined, {
    weekday: "short",
    timeZone: "UTC",
  });
}

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight, Radio } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoadingList } from "@/components/loading";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import { Chart } from "./charts";
import {
  getSportsGame,
  listGameSnapshots,
  listPlayerSnapshots,
  type GameSnapshot,
  type SportsGame,
  type SportsGameDetail,
  type SportsPlayer,
} from "./dashboard-api";
import { modeName, stamp } from "./format";
import { JsonTree } from "./json-tree";
import { Chip } from "./players";
import { Fact, PANEL } from "./panel";
import { Hint, StatusChip } from "./sports-parts";
import { LIVE_MS, periodName } from "./format";

export type SportsPanel = "score" | "timeline" | "players" | "json";

const PANELS: { id: SportsPanel; label: string }[] = [
  { id: "score", label: "Score" },
  { id: "timeline", label: "Timeline" },
  { id: "players", label: "Players" },
  { id: "json", label: "JSON" },
];

const statValue = (player: SportsPlayer, key: string) => {
  const value = player.stats[key];
  return typeof value === "number" ? value : null;
};

/** The sports explorer's game inspector: the scoreboard, then the score, timeline, players or raw JSON. */

/** Re-keys its child when `value` changes after the first render, so the highlight animation replays. */
function Changed({
  value,
  children,
  className,
}: {
  value: unknown;
  children: ReactNode;
  className?: string;
}) {
  const first = useRef(true);
  const [generation, setGeneration] = useState(0);
  const serialized = JSON.stringify(value);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    setGeneration((g) => g + 1);
  }, [serialized]);
  return (
    <span
      key={generation}
      className={cn("rounded px-0.5", generation > 0 && "animate-changed", className)}
    >
      {children}
    </span>
  );
}

export function GameInspector({
  user,
  gameId,
  panel,
  player,
  onPanel,
  onPlayer,
}: {
  user: User;
  gameId: string;
  panel: SportsPanel;
  player: string | null;
  onPanel: (panel: SportsPanel) => void;
  onPlayer: (player: string | null) => void;
}) {
  const detail = useQuery({
    queryKey: userKey(user.id, "admin-sports", "game", gameId),
    queryFn: ({ signal }) => getSportsGame(gameId, signal),
    refetchInterval: (query) => (query.state.data?.game.status === "LIVE" ? LIVE_MS : false),
    retry: false,
  });
  const live = detail.data?.game.status === "LIVE";
  const snapshots = useQuery({
    queryKey: userKey(user.id, "admin-sports", "snapshots", gameId),
    queryFn: ({ signal }) => listGameSnapshots(gameId, signal),
    enabled: panel === "timeline" || panel === "json",
    refetchInterval: live ? LIVE_MS : false,
    retry: false,
  });
  if (detail.isPending)
    return <LoadingList label="Loading the game" rows={4} rowClassName="h-20" />;
  if (detail.isError) return <p role="alert">{backendErrorMessage(detail.error)}</p>;
  const { game } = detail.data;
  return (
    <section aria-labelledby="game-heading" className={cn(PANEL, "space-y-4")}>
      <Scoreboard game={game} refreshedAt={detail.dataUpdatedAt} live={live} />
      <SegmentedTabs
        size="sm"
        ariaLabel="Inspector"
        tabs={PANELS}
        value={panel}
        onChange={onPanel}
      />
      {panel === "score" ? (
        <ScorePanel detail={detail.data} />
      ) : panel === "timeline" ? (
        snapshots.isPending ? (
          <LoadingList label="Loading snapshots" rows={3} rowClassName="h-16" />
        ) : snapshots.isError ? (
          <p role="alert">{backendErrorMessage(snapshots.error)}</p>
        ) : (
          <Timeline game={game} snapshots={snapshots.data} />
        )
      ) : panel === "players" ? (
        <PlayersPanel
          user={user}
          detail={detail.data}
          player={player}
          onPlayer={onPlayer}
          live={live}
        />
      ) : (
        <JsonTree
          label="game"
          value={{
            ...detail.data,
            snapshots: snapshots.data ?? `(${game.snapshots} snapshots loading)`,
          }}
        />
      )}
    </section>
  );
}

function Scoreboard({
  game,
  refreshedAt,
  live,
}: {
  game: SportsGame;
  refreshedAt: number;
  live: boolean;
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3
          id="game-heading"
          className="flex flex-wrap items-center gap-2 font-mono text-xs text-muted-foreground"
        >
          <StatusChip status={game.status} />
          <span>{game.id}</span>
          <span>· provider {game.providerId}</span>
        </h3>
        <p className="flex items-center gap-1 font-mono text-[11px] text-muted-foreground">
          {live && <Radio className="size-3 text-loss" />}
          {live ? "live, every 5 s" : "not live"} · read {stamp(refreshedAt)}
        </p>
      </div>
      <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 text-center">
        <TeamScore team={game.away} side="Away" />
        <div className="font-mono text-sm">
          <Changed value={[game.period, game.clock]}>
            {game.status === "LIVE"
              ? `${periodName(game.league, game.period)} ${game.clock ?? ""}`
              : game.status === "SCHEDULED"
                ? stamp(game.startsAt)
                : game.status.toLowerCase()}
          </Changed>
        </div>
        <TeamScore team={game.home} side="Home" />
      </div>
    </div>
  );
}

function TeamScore({ team, side }: { team: SportsGame["home"]; side: string }) {
  return (
    <div className="min-w-0">
      <p className="truncate text-xs text-muted-foreground">
        {side} · {team.city} {team.name}
      </p>
      <p className="font-display text-4xl font-bold">
        {team.abbreviation} <Changed value={team.score}>{team.score ?? "–"}</Changed>
      </p>
    </div>
  );
}

function LineScore({ game, away, home }: { game: SportsGame; away: number[]; home: number[] }) {
  const periods = Math.max(away.length, home.length, 4);
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[360px] text-center font-mono text-sm">
        <thead className="text-[10px] uppercase text-muted-foreground">
          <tr>
            <th className="py-1 text-left font-normal">Team</th>
            {Array.from({ length: periods }, (_, i) => (
              <th key={i} className="py-1 font-normal">
                {periodName(game.league, i + 1)}
              </th>
            ))}
            <th className="py-1 font-normal">T</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {[
            [game.away.abbreviation, away, game.away.score],
            [game.home.abbreviation, home, game.home.score],
          ].map(([name, scores, total]) => (
            <tr key={name as string}>
              <td className="py-1 text-left font-semibold">{name as string}</td>
              {Array.from({ length: periods }, (_, i) => (
                <td key={i} className="py-1">
                  <Changed value={(scores as number[])[i]}>
                    {(scores as number[])[i] ?? "–"}
                  </Changed>
                </td>
              ))}
              <td className="py-1 font-bold">{(total as number | null) ?? "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ScorePanel({ detail }: { detail: SportsGameDetail }) {
  const { game } = detail;
  return (
    <div className="space-y-4">
      <LineScore game={game} away={game.away.periods} home={game.home.periods} />
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
        <Fact label="Starts">{stamp(game.startsAt)}</Fact>
        <Fact label="Last change seen">{stamp(game.observedAt)}</Fact>
        <Fact label="First seen">
          {detail.firstObservedAt ? stamp(detail.firstObservedAt) : "–"}
        </Fact>
        <Fact label="Snapshots">{game.snapshots}</Fact>
        <Fact label="Players with stats">{detail.players.length}</Fact>
        <Fact label="Bets on it">{game.bets}</Fact>
      </dl>
      {detail.bets.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {detail.bets.map((row) => (
            <Chip key={`${row.mode}-${row.status}`}>
              {modeName(row.mode)} · {row.status.toLowerCase()} · {row.count}
            </Chip>
          ))}
        </div>
      )}
    </div>
  );
}

/** Every stored snapshot: the score over time, and a slider to step through them one change at a time. */
function Timeline({ game, snapshots }: { game: SportsGame; snapshots: GameSnapshot[] }) {
  const [index, setIndex] = useState<number | null>(null);
  const at = index ?? snapshots.length - 1;
  const current = snapshots[at];
  const previous = at > 0 ? snapshots[at - 1] : undefined;
  if (!current) return <Hint>No snapshots stored for this game.</Hint>;
  const labels = snapshots.map((s) =>
    new Date(s.observedAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }),
  );
  const changes = previous ? diff(previous, current) : ["first snapshot"];
  return (
    <div className="space-y-4">
      <Chart
        title={`${game.away.abbreviation} and ${game.home.abbreviation} score over ${snapshots.length} snapshots`}
        labels={labels}
        series={[
          {
            id: "away",
            label: game.away.abbreviation,
            color: "var(--viz-1)",
            values: snapshots.map((s) => s.awayScore),
          },
          {
            id: "home",
            label: game.home.abbreviation,
            color: "var(--viz-2)",
            values: snapshots.map((s) => s.homeScore),
          },
        ]}
      />
      <div className="space-y-2 rounded-lg border p-3">
        <div className="flex items-center gap-2">
          <Button
            size="icon"
            variant="outline"
            aria-label="Previous snapshot"
            disabled={at === 0}
            onClick={() => setIndex(at - 1)}
          >
            <ChevronLeft />
          </Button>
          <input
            type="range"
            min={0}
            max={snapshots.length - 1}
            value={at}
            aria-label="Snapshot"
            className="min-w-0 flex-1 accent-[var(--viz-1)]"
            onChange={(event) => setIndex(Number(event.target.value))}
          />
          <Button
            size="icon"
            variant="outline"
            aria-label="Next snapshot"
            disabled={at === snapshots.length - 1}
            onClick={() => setIndex(at + 1 === snapshots.length - 1 ? null : at + 1)}
          >
            <ChevronRight />
          </Button>
        </div>
        <p className="font-mono text-xs">
          Snapshot {at + 1} of {snapshots.length} · {stamp(current.observedAt)}
          {index === null && " · following the latest"}
        </p>
        <p className="text-sm">
          {game.away.abbreviation} {current.awayScore ?? 0}, {game.home.abbreviation}{" "}
          {current.homeScore ?? 0} · {current.status.toLowerCase()}{" "}
          {periodName(game.league, current.period)} {current.clock ?? ""}
          {current.providerState && (
            <span className="text-muted-foreground"> · provider “{current.providerState}”</span>
          )}
        </p>
        <p className="text-xs text-muted-foreground">Changed: {changes.join(", ")}</p>
        <LineScore game={game} away={current.awayPeriods} home={current.homePeriods} />
      </div>
    </div>
  );
}

function diff(before: GameSnapshot, after: GameSnapshot) {
  const changed: string[] = [];
  if (before.status !== after.status) changed.push(`status ${before.status} → ${after.status}`);
  if (before.period !== after.period)
    changed.push(`period ${before.period ?? "–"} → ${after.period ?? "–"}`);
  if (before.clock !== after.clock)
    changed.push(`clock ${before.clock ?? "–"} → ${after.clock ?? "–"}`);
  if (before.awayScore !== after.awayScore)
    changed.push(`away ${before.awayScore ?? "–"} → ${after.awayScore ?? "–"}`);
  if (before.homeScore !== after.homeScore)
    changed.push(`home ${before.homeScore ?? "–"} → ${after.homeScore ?? "–"}`);
  if (before.providerState !== after.providerState) changed.push("provider state");
  if (
    JSON.stringify([before.awayPeriods, before.homePeriods]) !==
    JSON.stringify([after.awayPeriods, after.homePeriods])
  )
    changed.push("period scores");
  return changed.length ? changed : ["nothing visible (provider resent)"];
}

function PlayersPanel({
  user,
  detail,
  player,
  onPlayer,
  live,
}: {
  user: User;
  detail: SportsGameDetail;
  player: string | null;
  onPlayer: (id: string | null) => void;
  live: boolean;
}) {
  const keys = useMemo(() => {
    const all = new Set<string>();
    for (const p of detail.players)
      for (const [key, value] of Object.entries(p.stats))
        if (typeof value === "number") all.add(key);
    return [...all];
  }, [detail.players]);
  const [sort, setSort] = useState<{ key: string; descending: boolean }>({
    key: keys[0] ?? "name",
    descending: true,
  });
  const [team, setTeam] = useState<"all" | string>("all");
  const rows = useMemo(() => {
    const filtered = detail.players.filter((p) => team === "all" || p.team === team);
    return [...filtered].sort((a, b) => {
      const difference =
        sort.key === "name"
          ? a.name.localeCompare(b.name)
          : (statValue(a, sort.key) ?? -1) - (statValue(b, sort.key) ?? -1);
      return sort.descending ? -difference : difference;
    });
  }, [detail.players, sort, team]);
  const chosen = detail.players.find((p) => p.id === player) ?? null;
  if (detail.players.length === 0) return <Hint>No player stats stored for this game.</Hint>;
  return (
    <div className="space-y-4">
      <SegmentedTabs
        size="sm"
        ariaLabel="Team"
        tabs={[
          { id: "all", label: "Both teams" },
          { id: detail.game.away.abbreviation, label: detail.game.away.abbreviation },
          { id: detail.game.home.abbreviation, label: detail.game.home.abbreviation },
        ]}
        value={team}
        onChange={setTeam}
      />
      <div className="max-h-[50vh] overflow-auto rounded-lg border">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-surface text-left font-mono text-[10px] uppercase text-muted-foreground">
            <tr>
              {["name", ...keys].map((key) => (
                <th
                  key={key}
                  className={cn("px-2 py-1.5 font-normal", key !== "name" && "text-right")}
                >
                  <button
                    type="button"
                    className="inline-flex items-center gap-0.5 uppercase hover:text-foreground"
                    onClick={() =>
                      setSort((s) => ({
                        key,
                        descending: s.key === key ? !s.descending : key !== "name",
                      }))
                    }
                  >
                    {key === "name" ? "Player" : key}
                    {sort.key === key &&
                      (sort.descending ? (
                        <ArrowDown className="size-3" />
                      ) : (
                        <ArrowUp className="size-3" />
                      ))}
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y">
            {rows.map((row) => (
              <tr
                key={row.id}
                className={cn(
                  "cursor-pointer hover:bg-muted/50",
                  player === row.id && "bg-primary/10",
                  !row.played && "text-muted-foreground",
                )}
                onClick={() => onPlayer(player === row.id ? null : row.id)}
              >
                <td className="whitespace-nowrap px-2 py-1">
                  <button
                    type="button"
                    className="text-left"
                    onClick={(e) => {
                      e.stopPropagation();
                      onPlayer(player === row.id ? null : row.id);
                    }}
                  >
                    <span className="font-semibold">{row.name}</span>{" "}
                    <span className="text-xs text-muted-foreground">
                      {[row.position, row.team].filter(Boolean).join(" · ")}
                      {!row.played && " · DNP"}
                    </span>
                  </button>
                </td>
                {keys.map((key) => (
                  <td key={key} className="px-2 py-1 text-right font-mono">
                    {live ? (
                      <Changed value={row.stats[key]}>{statValue(row, key) ?? "–"}</Changed>
                    ) : (
                      (statValue(row, key) ?? "–")
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {chosen && (
        <PlayerHistory
          user={user}
          gameId={detail.game.id}
          player={chosen}
          keys={keys}
          live={live}
        />
      )}
    </div>
  );
}

function PlayerHistory({
  user,
  gameId,
  player,
  keys,
  live,
}: {
  user: User;
  gameId: string;
  player: SportsPlayer;
  keys: string[];
  live: boolean;
}) {
  const history = useQuery({
    queryKey: userKey(user.id, "admin-sports", "player", gameId, player.id),
    queryFn: ({ signal }) => listPlayerSnapshots(gameId, player.id, signal),
    refetchInterval: live ? LIVE_MS : false,
    retry: false,
  });
  const [stat, setStat] = useState(keys.includes("pts") ? "pts" : (keys[0] ?? ""));
  return (
    <section className="space-y-3 rounded-lg border p-3" aria-label={`${player.name} history`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="font-semibold">
          {player.name}{" "}
          <span className="font-mono text-xs text-muted-foreground">
            {player.id} · {player.snapshots} snapshots
          </span>
        </h4>
        <select
          aria-label="Stat"
          className="h-8 rounded-md border bg-surface px-2 text-sm"
          value={stat}
          onChange={(event) => setStat(event.target.value)}
        >
          {keys.map((key) => (
            <option key={key} value={key}>
              {key}
            </option>
          ))}
        </select>
      </div>
      {history.isPending ? (
        <LoadingList label="Loading stat history" rows={2} rowClassName="h-16" />
      ) : history.isError ? (
        <p role="alert">{backendErrorMessage(history.error)}</p>
      ) : (
        <>
          <Chart
            title={`${player.name} ${stat} over ${history.data.length} snapshots`}
            labels={history.data.map((s) =>
              new Date(s.observedAt).toLocaleTimeString(undefined, {
                hour: "2-digit",
                minute: "2-digit",
              }),
            )}
            series={[
              {
                id: stat,
                label: stat,
                color: "var(--viz-1)",
                values: history.data.map((s) =>
                  typeof s.stats[stat] === "number" ? (s.stats[stat] as number) : null,
                ),
              },
            ]}
            height={150}
          />
          <JsonTree label={`${player.id}.snapshots`} value={history.data} />
        </>
      )}
    </section>
  );
}

import { useEffect } from "react";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { LoadingList } from "@/components/loading";
import { forStat, type GamePlayer, type GamePlayers } from "@/features/sports";
import { halfLine, playerCount, startingLine, type PlayerMode } from "@/features/bets/sports-model";
import { Section, ValueSlider } from "./form-parts";

/**
 * Setup for the player modes: the stat, one or two players from the game (with their current number), and the line
 * (Prop Hunt) or target (King Of The Hill). The server rechecks everything.
 */
export function PlayerSetup({
  mode,
  roster,
  stat,
  onStat,
  playerIds,
  onPlayers,
  line,
  onLine,
}: {
  mode: PlayerMode;
  roster: GamePlayers | undefined;
  stat: string | null;
  onStat: (stat: string) => void;
  playerIds: string[];
  onPlayers: (ids: string[]) => void;
  line: number;
  onLine: (line: number) => void;
}) {
  // The lowest line or target that isn't already reached: just above the chosen players' current numbers. It
  // follows them as the game goes on (the roster refreshes), so a bet that would wash at lock can't be proposed.
  const chosen = playerIds.flatMap((id) => roster?.players.find((p) => p.id === id) ?? []);
  const floor =
    mode === "either_or" || !stat || chosen.length === 0
      ? null
      : startingLine(
          mode,
          chosen.map((p) => p.stats[stat] ?? 0),
        );
  useEffect(() => {
    if (floor !== null && line < floor) onLine(floor);
  }, [floor, line, onLine]);
  if (!roster) return <LoadingList label="Loading players" rows={3} rowClassName="h-10" />;
  if (roster.players.length === 0)
    return (
      <p className="text-sm text-muted-foreground">
        No players for this game yet. Pick another game, or try again closer to the start.
      </p>
    );
  const statLabel = roster.stats.find((s) => s.key === stat)?.label ?? "";
  const slots = Array.from({ length: playerCount(mode) }, (_, i) => i);
  const current = (player: GamePlayer) => (stat ? (player.stats[stat] ?? 0) : 0);
  // The stat's positions first, by value; everyone else under a divider (sports/roster-order).
  const ordered = forStat(roster.players, stat ?? "");
  // A player chosen in another slot isn't offered again.
  const free = (slot: number) => (p: GamePlayer) =>
    !playerIds.includes(p.id) || playerIds[slot] === p.id;
  const item = (p: GamePlayer) => (
    <SelectItem key={p.id} value={p.id}>
      {p.position ? `${p.position} · ` : ""}
      {p.name} · {p.team ?? "?"} · {current(p)} {statLabel.toLowerCase()}
    </SelectItem>
  );

  function choose(slot: number, id: string) {
    const next = [...playerIds];
    next[slot] = id;
    onPlayers(next.filter(Boolean));
  }

  return (
    <Section label="Setup">
      <div className="grid gap-1.5 text-xs font-medium text-muted-foreground">
        Stat
        <SegmentedTabs<string>
          size="sm"
          ariaLabel="Stat"
          tabs={roster.stats.map((s) => ({ id: s.key, label: s.label }))}
          value={stat ?? ""}
          onChange={onStat}
        />
      </div>
      {slots.map((slot) => (
        <div key={slot} className="mt-3 grid gap-1.5 text-xs font-medium text-muted-foreground">
          {slots.length === 1 ? "Player" : `Player ${slot + 1}`}
          <Select value={playerIds[slot] ?? ""} onValueChange={(id) => choose(slot, id)}>
            <SelectTrigger
              className="h-10 bg-surface"
              aria-label={slots.length === 1 ? "Player" : `Player ${slot + 1}`}
            >
              <SelectValue placeholder="Choose a player" />
            </SelectTrigger>
            <SelectContent>
              {ordered.main.filter(free(slot)).map(item)}
              {ordered.others.some(free(slot)) && (
                <>
                  <SelectSeparator />
                  <SelectGroup>
                    <SelectLabel>Other players</SelectLabel>
                    {ordered.others.filter(free(slot)).map(item)}
                  </SelectGroup>
                </>
              )}
            </SelectContent>
          </Select>
        </div>
      ))}
      {mode !== "either_or" && (
        <div className="mt-3 grid gap-1.5 text-xs font-medium text-muted-foreground">
          {mode === "prop_hunt" ? `${statLabel} line` : `Target (${statLabel.toLowerCase()})`}
          <ValueSlider
            value={line}
            // A Prop Hunt line ends in .5 (never a push); a target is a whole number. Both move by 1.
            step={1}
            min={floor ?? (mode === "prop_hunt" ? 0.5 : 1)}
            max={mode === "prop_hunt" ? 999.5 : 999}
            label={mode === "prop_hunt" ? "Stat line" : "Target"}
            // Room for any game: yards run into the hundreds (passing record 554); counting stats stay under 100.
            sliderMax={(stat?.includes("yards") ? 600 : 100) - (mode === "prop_hunt" ? 0.5 : 0)}
            onChange={(v) =>
              onLine(Math.max(floor ?? 0, mode === "prop_hunt" ? halfLine(v, 0.5, 999.5) : v))
            }
          />
          {chosen.length > 0 && stat && (
            <span className="font-normal">
              Now: {chosen.map((p) => `${p.name} ${p.stats[stat] ?? 0}`).join(" · ")}{" "}
              {statLabel.toLowerCase()}. The {mode === "prop_hunt" ? "line" : "target"} starts above
              that.
            </span>
          )}
        </div>
      )}
    </Section>
  );
}

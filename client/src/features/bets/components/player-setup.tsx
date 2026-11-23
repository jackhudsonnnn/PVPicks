import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { LoadingList } from "@/components/loading";
import { byPosition, type GamePlayer, type GamePlayers } from "@/features/sports";
import { halfLine, playerCount, type PlayerMode } from "@/features/bets/sports-model";
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
              {byPosition(roster.players)
                .filter((p) => !playerIds.includes(p.id) || playerIds[slot] === p.id)
                .map((p) => (
                  <SelectItem key={p.id} value={p.id}>
                    {p.position ? `${p.position} · ` : ""}
                    {p.name} · {p.team ?? "?"} · {current(p)} {statLabel.toLowerCase()}
                  </SelectItem>
                ))}
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
            min={mode === "prop_hunt" ? 0.5 : 1}
            max={mode === "prop_hunt" ? 999.5 : 999}
            label={mode === "prop_hunt" ? "Stat line" : "Target"}
            // Room for any game: yards run into the hundreds (passing record 554); counting stats stay under 100.
            sliderMax={(stat?.includes("yards") ? 600 : 100) - (mode === "prop_hunt" ? 0.5 : 0)}
            onChange={(v) => onLine(mode === "prop_hunt" ? halfLine(v, 0.5, 999.5) : v)}
          />
          <span className="font-normal">
            {mode === "prop_hunt"
              ? "It has to start above the player's number; if they pass it before picks lock, the bet washes."
              : "It has to start above both players' numbers; if either reaches it before picks lock, the bet washes. If neither gets there, Neither wins."}
          </span>
        </div>
      )}
    </Section>
  );
}

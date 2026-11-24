import { tickerStatus, type GamePlayer, type TickerGame } from "@/features/sports";
import { FactRow, FactSection } from "./details-parts";

/**
 * A sports proposal's preview: where the game stands right now, and the numbers the bet follows (each chosen
 * player's stat, the combined score or the home margin), so the proposer sees what the line is up against.
 */
export function GameNow({
  game,
  mode,
  players,
  stat,
  statLabel,
}: {
  game: TickerGame;
  mode: string;
  players: GamePlayer[];
  stat: string | null;
  statLabel: string;
}) {
  const away = game.away.score ?? 0;
  const home = game.home.score ?? 0;
  const live = game.status === "LIVE";
  return (
    <FactSection title="Right now">
      <FactRow label="Game">
        {game.away.abbreviation} {live ? away : ""} @ {game.home.abbreviation} {live ? home : ""} ·{" "}
        {tickerStatus(game)}
      </FactRow>
      {mode === "total_disaster" && <FactRow label="Combined score">{away + home}</FactRow>}
      {mode === "give_and_take" && (
        <FactRow label={`${game.home.abbreviation} margin`}>
          {home - away > 0 ? `+${home - away}` : home - away}
        </FactRow>
      )}
      {stat &&
        players.map((p) => (
          <FactRow key={p.id} label={`${p.name} ${statLabel.toLowerCase()}`}>
            {p.stats[stat] ?? 0}
          </FactRow>
        ))}
    </FactSection>
  );
}

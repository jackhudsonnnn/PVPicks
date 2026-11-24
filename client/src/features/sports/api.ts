import { z } from "zod";
import { BackendError, read, request } from "@/lib/api-client";
import { timeLabel } from "@/lib/time";

const team = z.object({
  abbreviation: z.string().min(1),
  score: z.number().int().min(0).nullable(),
});
const gameSchema = z.object({
  id: z.string().min(1),
  league: z.enum(["NBA", "NFL"]),
  status: z.enum(["SCHEDULED", "LIVE", "FINAL", "POSTPONED"]),
  startsAt: z.string().datetime({ offset: true }),
  period: z.number().int().min(1).nullable().optional(),
  clock: z.string().nullable().optional(),
  home: team,
  away: team,
  observedAt: z.string().datetime({ offset: true }),
  /** A dev data game (dev backend only), not a real one. */
  dev: z.boolean().default(false),
});
const snapshotSchema = z.object({ games: z.array(gameSchema) });

export type TickerGame = z.infer<typeof gameSchema>;

/** The ticker's games, or null when live scores are unavailable (never shown as "no games"). */
export async function getTicker(signal?: AbortSignal): Promise<TickerGame[] | null> {
  let response: Response;
  try {
    response = await request("/ticker", signal ? { signal } : undefined);
  } catch (error) {
    if (error instanceof BackendError && error.status === 503) return null;
    throw error;
  }
  const result = snapshotSchema.safeParse(await response.json().catch(() => null));
  if (!result.success) throw new BackendError("The backend returned an unexpected response.");
  return result.data.games;
}

/** The status text shown beside a game's score. */
export function tickerStatus(game: TickerGame): string {
  switch (game.status) {
    case "LIVE":
      return game.clock ?? (game.period ? `Period ${game.period}` : "Live");
    case "FINAL":
      return "Final";
    case "POSTPONED":
      return "Postponed";
    case "SCHEDULED":
      return timeLabel(game.startsAt);
  }
}

const gamePlayerSchema = z.object({
  id: z.string().min(1),
  name: z.string(),
  position: z.string().nullable(),
  team: z.string().nullable(),
  played: z.boolean(),
  stats: z.record(z.string(), z.number().int()),
  /** This season's per-game averages by stat key, when the provider has them. */
  averages: z.record(z.string(), z.number()).optional(),
});
const gamePlayersSchema = z.object({
  stats: z.array(z.object({ key: z.string().min(1), label: z.string().min(1) })),
  players: z.array(gamePlayerSchema),
});

export type GamePlayer = z.infer<typeof gamePlayerSchema>;
export type GamePlayers = z.infer<typeof gamePlayersSchema>;

/** A game's pickable players with their current stats, and the league's stats, for the player modes. */
export async function getGamePlayers(gameId: string, signal?: AbortSignal): Promise<GamePlayers> {
  return read(
    await request(`/games/${encodeURIComponent(gameId)}/players`, signal ? { signal } : undefined),
    gamePlayersSchema,
  );
}

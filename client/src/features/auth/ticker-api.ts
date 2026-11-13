import { z } from "zod";
import { BackendError, request } from "./backend-api";

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
export function tickerStatus(game: TickerGame, locale?: string): string {
  switch (game.status) {
    case "LIVE":
      return game.clock ?? (game.period ? `Period ${game.period}` : "Live");
    case "FINAL":
      return "Final";
    case "POSTPONED":
      return "Postponed";
    case "SCHEDULED":
      return new Date(game.startsAt).toLocaleTimeString(locale, {
        hour: "numeric",
        minute: "2-digit",
      });
  }
}

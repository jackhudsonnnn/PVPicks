import { z } from "zod";
import { BackendError, mutationRequest, read, request } from "@/lib/api-client";

const source = z.object({
  name: z.string(),
  title: z.string(),
  description: z.string(),
  league: z.enum(["NBA", "NFL"]).nullable(),
  running: z.boolean(),
  error: z.string().optional(),
  startedAt: z.string().optional(),
  games: z.number().int().optional(),
  players: z.number().int().optional(),
  lengthSeconds: z.number().int().optional(),
  positionSeconds: z.number().int().optional(),
  speed: z.number().optional(),
  paused: z.boolean().optional(),
});
const listSchema = z.object({
  speeds: z.array(z.number()),
  scenarios: z.array(source),
  chunks: z.array(source),
});

export type DevSource = z.infer<typeof source>;
export type DevSports = z.infer<typeof listSchema>;
export type DevKind = "scenarios" | "chunks";

/** Scenarios and chunks with what's running; null when the backend has dev data turned off (404). */
export async function getDevSports(signal?: AbortSignal): Promise<DevSports | null> {
  try {
    return await read(await request("/dev/sports", signal ? { signal } : undefined), listSchema);
  } catch (error) {
    if (error instanceof BackendError && error.status === 404) return null;
    throw error;
  }
}

export async function devAction(
  kind: DevKind,
  name: string,
  action: "start" | "stop" | "pause" | "resume" | "speed",
  speed?: number,
) {
  await mutationRequest(
    `/dev/sports/${kind}/${encodeURIComponent(name)}/${action}`,
    "POST",
    crypto.randomUUID(),
    action === "speed" ? { speed } : undefined,
  );
}

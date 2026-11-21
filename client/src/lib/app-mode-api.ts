import { z } from "zod";
import { read, request } from "@/lib/api-client";

/**
 * Which of the app's three settings the backend runs as (`GET /config`): `dev` (local development, with the demo
 * picker), `lan` (the LAN watch party: sign-in codes and the party table) or `prod` (not configured yet). Code that
 * behaves the same everywhere never asks.
 */
export type AppMode = "dev" | "lan" | "prod";

const modeSchema = z.object({ mode: z.enum(["dev", "lan", "prod"]) });

export async function getAppMode(signal?: AbortSignal): Promise<AppMode> {
  return (await read(await request("/config", signal ? { signal } : undefined), modeSchema)).mode;
}

import { z } from "zod";
import { BackendError, csrf, idSchema, mutationRequest, read, request } from "@/lib/api-client";

const person = z.object({
  id: idSchema,
  username: z.string().nullable(),
  hue: z.number().int().min(1).max(5).nullable(),
  status: z.enum(["ACTIVE", "FROZEN", "DELETED"]),
  strikes: z.number().int().min(0).max(3),
});
const reportSchema = z.object({
  id: idSchema,
  reason: z.enum(["harassment", "settlement", "spam", "offensive", "other"]),
  note: z.string(),
  status: z.enum(["OPEN", "RESOLVED", "DISMISSED"]),
  createdAt: z.string().datetime({ offset: true }),
  reporter: person,
  target: person,
  tableId: idSchema.nullable().optional(),
  tableName: z.string().nullable().optional(),
  messageSnapshot: z.string().nullable().optional(),
});

export type ModerationPerson = z.infer<typeof person>;
export type ModerationReport = z.infer<typeof reportSchema>;

/** A page of an admin list (50 at most); pass `nextCursor` back for the next one. */
export interface AdminPage<T> {
  items: T[];
  nextCursor: string | null;
}

export const page = <T extends z.ZodTypeAny>(item: T) =>
  z.object({ items: z.array(item).max(50), nextCursor: z.string().min(1).nullable() });

export const withCursor = (path: string, cursor: string | null) =>
  cursor === null
    ? path
    : `${path}${path.includes("?") ? "&" : "?"}cursor=${encodeURIComponent(cursor)}`;

/** The moderation queue, newest first, 50 at a time. */
export async function listReports(
  all: boolean,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<AdminPage<ModerationReport>> {
  return read(
    await request(
      withCursor(`/admin/reports?status=${all ? "all" : "open"}`, cursor),
      signal ? { signal } : undefined,
    ),
    page(reportSchema),
  );
}

/** Frozen first, then most strikes, then by name, 50 at a time. */
/** Players with strikes or a frozen account; `search` keeps usernames starting with it. */
export async function listFlagged(
  cursor: string | null,
  signal?: AbortSignal,
  search = "",
): Promise<AdminPage<ModerationPerson>> {
  const path = search.trim()
    ? `/admin/users/flagged?q=${encodeURIComponent(search.trim())}`
    : "/admin/users/flagged";
  return read(
    await request(withCursor(path, cursor), signal ? { signal } : undefined),
    page(person),
  );
}

const valid = (id: string) => {
  if (!idSchema.safeParse(id).success) throw new BackendError("Not found.", 404);
};

/**
 * Admin actions. Each is one-shot on the server (a second strike or dismissal of the same report answers
 * "already handled"), so a fresh key per attempt is safe and there's no saved retry.
 */
export async function moderate(
  action: "dismiss" | "strike",
  reportId: string,
): Promise<{ strikes: number; frozen: boolean } | null> {
  valid(reportId);
  const response = await mutationRequest(
    `/admin/reports/${encodeURIComponent(reportId)}/${action}`,
    "POST",
    crypto.randomUUID(),
  );
  if (action === "dismiss" || response.status === 204) return null;
  return read(response, z.object({ strikes: z.number().int(), frozen: z.boolean() }));
}

export async function changeUser(action: "unfreeze" | "clear-strikes", userId: string) {
  valid(userId);
  await mutationRequest(
    `/admin/users/${encodeURIComponent(userId)}/${action}`,
    "POST",
    crypto.randomUUID(),
  );
}

/** A player's sign-in code: 4 characters, no look-alikes. */
const signInCode = z.object({ code: z.string().regex(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}$/) });
const playerSchema = z.object({
  id: idSchema,
  username: z.string(),
  hue: z.number().int().min(1).max(5),
  status: z.enum(["ACTIVE", "FROZEN"]),
  lastSignInAt: z.string().datetime({ offset: true }).nullable(),
  codeRotatedAt: z.string().datetime({ offset: true }).nullable(),
  createdAt: z.string().datetime({ offset: true }),
  strikes: z.number().int().min(0).max(3),
  openReports: z.number().int().min(0),
});

export type AdminPlayer = z.infer<typeof playerSchema>;

/** Players, newest first, 50 at a time; `search` keeps usernames starting with it. */
export async function listPlayers(
  cursor: string | null,
  signal?: AbortSignal,
  search = "",
): Promise<AdminPage<AdminPlayer>> {
  const path = search.trim()
    ? `/admin/players?q=${encodeURIComponent(search.trim().toLowerCase())}`
    : "/admin/players";
  return read(
    await request(withCursor(path, cursor), signal ? { signal } : undefined),
    page(playerSchema),
  );
}

async function post(path: string, body?: object) {
  const token = await csrf();
  return request(path, {
    method: "POST",
    headers: {
      "X-CSRF-TOKEN": token,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

/** Creates a player, with their sign-in code. */
export async function createPlayer(username: string) {
  return read(
    await post("/admin/players", { username }),
    signInCode.extend({ id: idSchema, username: z.string() }),
  );
}

/** A player's current sign-in code, for their QR card. */
export async function getPlayerCode(playerId: string, signal?: AbortSignal): Promise<string> {
  return (
    await read(
      await request(
        `/admin/players/${encodeURIComponent(playerId)}/code`,
        signal ? { signal } : undefined,
      ),
      signInCode,
    )
  ).code;
}

/** Issues the player a new code; their old code and every signed-in device stop working. */
export async function rotatePlayerCode(playerId: string): Promise<string> {
  return (await read(await post(`/admin/players/${encodeURIComponent(playerId)}/code`), signInCode))
    .code;
}

/** The QR link a player opens; the code stays in the fragment, so it never reaches server logs. */
/** A player's QR link: their id (stable across renames) and code, in the fragment so neither reaches any log. */
export const signInLink = (playerId: string, code: string) =>
  `${window.location.origin}/signin#${playerId}.${code}`;

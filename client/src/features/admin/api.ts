import { z } from "zod";
import { BackendError, csrf, idSchema, mutationRequest, read, request } from "@/lib/api-client";

const person = z.object({
  id: idSchema,
  username: z.string().nullable(),
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

const page = <T extends z.ZodTypeAny>(item: T) =>
  z.object({ items: z.array(item).max(50), nextCursor: z.string().min(1).nullable() });

const withCursor = (path: string, cursor: string | null) =>
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
export async function listFlagged(
  cursor: string | null,
  signal?: AbortSignal,
): Promise<AdminPage<ModerationPerson>> {
  return read(
    await request(withCursor("/admin/users/flagged", cursor), signal ? { signal } : undefined),
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

const setupCode = z.object({
  setupCode: z.string().min(1),
  purpose: z.enum(["SETUP", "RESET"]),
  expiresAt: z.string().datetime({ offset: true }),
});
const playerSchema = z.object({
  id: idSchema,
  username: z.string(),
  status: z.enum(["ACTIVE", "FROZEN"]),
  passwordSet: z.boolean(),
  codeExpiresAt: z.string().datetime({ offset: true }).nullable(),
  createdAt: z.string().datetime({ offset: true }),
});

export type SetupCode = z.infer<typeof setupCode>;
export type AdminPlayer = z.infer<typeof playerSchema>;

/** Players, newest first, 50 at a time. */
export async function listPlayers(
  cursor: string | null,
  signal?: AbortSignal,
): Promise<AdminPage<AdminPlayer>> {
  return read(
    await request(withCursor("/admin/players", cursor), signal ? { signal } : undefined),
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

/** Creates a player; the response's setup code is shown once and never again. */
export async function createPlayer(username: string) {
  return read(
    await post("/admin/players", { username }),
    setupCode.extend({ id: idSchema, username: z.string() }),
  );
}

/** A new setup (never signed in) or reset code for a player; it cancels their unused one. */
export async function issuePlayerCode(playerId: string): Promise<SetupCode> {
  return read(await post(`/admin/players/${encodeURIComponent(playerId)}/code`), setupCode);
}

/** The link a player opens; the code stays in the fragment, so it never reaches server logs. */
export const setupLink = (code: string) => `${window.location.origin}/setup#${code}`;

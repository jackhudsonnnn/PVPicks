import { z } from "zod";
import { BackendError, idSchema, mutationRequest, read, request } from "@/lib/api-client";

const uuid = idSchema;

const tableSchema = z.object({
  id: z.guid(),
  name: z.string(),
  emoji: z.string(),
  memberIds: z.array(z.guid()),
  hostIds: z.array(z.guid()).min(1),
  hostsOnlyBets: z.boolean(),
  u2pickEnabled: z.boolean(),
  /** Whether members can start new chat polls (existing polls stay open either way). */
  pollsEnabled: z.boolean(),
  /** Who may propose Jackblack here; hosts set it. */
  jackblackAccess: z.enum(["hosts", "everyone", "off"]),
  quickPicksEnabled: z.boolean(),
  quickPickSharing: z.boolean(),
  quickPickCode: z.string().optional(),
  subscribedTo: z.array(z.guid()),
  disabledPermissions: z.record(
    z.string(),
    z.array(z.enum(["bets", "text", "react", "gifs", "polls"])),
  ),
});

export type Table = z.infer<typeof tableSchema>;
export type JackblackAccess = Table["jackblackAccess"];

/** Most tables one account can be in, and most members one table can have (enforced by the server). */
export const TABLES_PER_USER = 100;
export const MEMBERS_PER_TABLE = 500;

const tableSummarySchema = z.object({
  id: z.guid(),
  name: z.string(),
  emoji: z.string(),
  /** Whether you host it. */
  host: z.boolean(),
  memberCount: z.number().int().min(1),
  /** The first few members by join order, for avatars; never the whole list. */
  memberPreview: z.array(z.guid()).max(4),
  /** Your own permissions a host turned off here. */
  myDisabledPermissions: z.array(z.enum(["bets", "text", "react", "gifs", "polls"])),
  /** When the most recent bet here was proposed; absent before the first one. */
  lastBetAt: z.string().datetime({ offset: true }).optional(),
  /** When you joined: the order's fallback for a table with no bets since. */
  joinedAt: z.string().datetime({ offset: true }),
});

/** One of your tables in a list (Home, the sidebar, tickets). `getTable` has the whole table. */
export type TableSummary = z.infer<typeof tableSummarySchema>;

/** What a ticket shows about its table. Both a whole table and a summary have it. */
export type TableRef = Pick<Table, "id" | "name" | "emoji">;

/** Whether you can bet at a table, from either the whole table or your summary of it. */
export function canBetAt(table: Table | TableSummary, userId: string): boolean {
  return "myDisabledPermissions" in table
    ? !table.myDisabledPermissions.includes("bets")
    : !table.disabledPermissions[userId]?.includes("bets");
}

/** Your tables (at most 100), most recent activity first. */
export async function listTables(signal?: AbortSignal): Promise<TableSummary[]> {
  return read(
    await request("/tables", signal ? { signal } : undefined),
    z.array(tableSummarySchema).max(TABLES_PER_USER),
  );
}

export async function getTable(id: string, signal?: AbortSignal): Promise<Table> {
  if (!uuid.safeParse(id).success) throw new BackendError("Table not found.", 404);
  return read(
    await request(`/tables/${encodeURIComponent(id)}`, signal ? { signal } : undefined),
    tableSchema,
  );
}

const outcomeSchema = z.object({
  userId: uuid,
  outcome: z.enum(["added", "invited", "not_added"]),
});
const archiveSchema = z.object({
  id: uuid,
  name: z.string(),
  emoji: z.string(),
  archivedAt: z.string().datetime({ offset: true }),
  expiresAt: z.string().datetime({ offset: true }),
});

const cents = z.union([z.number().int().safe(), z.bigint()]).transform((value) => BigInt(value));
const balanceSchema = z.object({ userId: uuid, settleCents: cents, trueCents: cents });

export type MemberOutcome = z.infer<typeof outcomeSchema>;
/** A member's points at a table: what the next settlement moves, and that minus their locked wagers. */
export type TableBalance = z.infer<typeof balanceSchema>;
type TableArchive = z.infer<typeof archiveSchema>;
export type Permission = "bets" | "text" | "react" | "gifs" | "polls";

function tablePath(tableId: string) {
  if (!uuid.safeParse(tableId).success) throw new BackendError("Table not found.", 404);
  return `/tables/${encodeURIComponent(tableId)}`;
}

function userPath(userId: string) {
  if (!uuid.safeParse(userId).success) throw new BackendError("Person not found.", 404);
  return encodeURIComponent(userId);
}

/** Creates a table; requested members are added, invited or not added according to their preferences. */
export async function createTable(
  input: { name: string; emoji: string; memberIds: string[] },
  key: string,
): Promise<{ tableId: string; outcomes: MemberOutcome[] }> {
  const result = await read(
    await mutationRequest("/tables", "POST", key, input),
    z.object({ table: tableSchema, outcomes: z.array(outcomeSchema) }),
  );
  return { tableId: result.table.id, outcomes: result.outcomes };
}

export async function updateTableProfile(
  tableId: string,
  change: { name?: string; emoji?: string },
  key: string,
) {
  await mutationRequest(tablePath(tableId), "PATCH", key, change);
}

export async function updateTableSettings(
  tableId: string,
  change: {
    hostsOnlyBets?: boolean;
    u2pickEnabled?: boolean;
    pollsEnabled?: boolean;
    jackblackAccess?: JackblackAccess;
  },
  key: string,
) {
  await mutationRequest(`${tablePath(tableId)}/settings`, "PATCH", key, change);
}

export async function deleteTable(tableId: string, key: string) {
  await mutationRequest(tablePath(tableId), "DELETE", key);
}

export async function addMembers(
  tableId: string,
  userIds: string[],
  key: string,
): Promise<MemberOutcome[]> {
  return read(
    await mutationRequest(`${tablePath(tableId)}/members`, "POST", key, { userIds }),
    z.array(outcomeSchema),
  );
}

export async function removeMember(tableId: string, userId: string, key: string) {
  await mutationRequest(`${tablePath(tableId)}/members/${userPath(userId)}`, "DELETE", key);
}

export async function setHost(tableId: string, userId: string, host: boolean, key: string) {
  await mutationRequest(
    `${tablePath(tableId)}/hosts/${userPath(userId)}`,
    host ? "PUT" : "DELETE",
    key,
  );
}

export async function setPermission(
  tableId: string,
  userId: string,
  permission: Permission,
  enabled: boolean,
  key: string,
) {
  await mutationRequest(
    `${tablePath(tableId)}/members/${userPath(userId)}/permissions`,
    "PUT",
    key,
    {
      permission,
      enabled,
    },
  );
}

export async function listArchives(signal?: AbortSignal): Promise<TableArchive[]> {
  return read(
    await request("/tables/archives", signal ? { signal } : undefined),
    z.array(archiveSchema),
  );
}

export async function listTableBalances(
  tableId: string,
  signal?: AbortSignal,
): Promise<TableBalance[]> {
  if (!uuid.safeParse(tableId).success) throw new BackendError("Table not found.", 404);
  return read(
    await request(
      `/tables/${encodeURIComponent(tableId)}/balances`,
      signal ? { signal } : undefined,
    ),
    z.array(balanceSchema),
  );
}

export async function setQuickPicksEnabled(tableId: string, enabled: boolean, key: string) {
  await mutationRequest(`${tablePath(tableId)}/quick-picks`, "PUT", key, { enabled });
}

export async function setQuickPickSharing(tableId: string, sharing: boolean, key: string) {
  await mutationRequest(
    `${tablePath(tableId)}/quick-picks/share`,
    sharing ? "POST" : "DELETE",
    key,
  );
}

/** A new code; old subscribers are disconnected. */
export async function rotateQuickPickCode(tableId: string, key: string): Promise<string> {
  return (
    await read(
      await mutationRequest(`${tablePath(tableId)}/quick-picks/rotate`, "POST", key),
      z.object({ code: z.string().regex(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/) }),
    )
  ).code;
}

const tomatoSchema = z.object({
  id: uuid,
  throwerId: uuid,
  targetId: uuid,
  thrownAt: z.string().datetime({ offset: true }),
});
export type TomatoThrow = z.infer<typeof tomatoSchema>;

/** Tomatoes thrown at the table in the last 30 seconds, without any involving people you're blocked with. */
export async function listTomatoes(tableId: string, signal?: AbortSignal): Promise<TomatoThrow[]> {
  return read(
    await request(`${tablePath(tableId)}/tomatoes`, signal ? { signal } : undefined),
    z.array(tomatoSchema),
  );
}

export async function throwTomato(tableId: string, targetId: string, key: string) {
  await mutationRequest(`${tablePath(tableId)}/tomatoes`, "POST", key, { targetId });
}

/** Members of the table whose username contains `query`, by username, 50 at a time. */
export async function searchTableMembers(
  tableId: string,
  query: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<{ items: string[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ q: query });
  if (cursor) params.set("cursor", cursor);
  return read(
    await request(`${tablePath(tableId)}/members?${params}`, signal ? { signal } : undefined),
    z.object({ items: z.array(uuid).max(50), nextCursor: z.string().nullable() }),
  );
}

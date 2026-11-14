import { z } from "zod";
import { BackendError, mutationRequest, read, request, tableSchema } from "./backend-api";

const uuid = z.guid();
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
export type TableArchive = z.infer<typeof archiveSchema>;
export type Permission = "bets" | "text" | "react" | "gifs";
export type RemainderRule = "host" | "proposer" | "earliest" | "random";

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
  change: { hostsOnlyBets?: boolean; u2pickEnabled?: boolean },
  key: string,
) {
  await mutationRequest(`${tablePath(tableId)}/settings`, "PATCH", key, change);
}

export async function setRemainderRule(tableId: string, rule: RemainderRule, key: string) {
  await mutationRequest(`${tablePath(tableId)}/remainder`, "PATCH", key, { rule });
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

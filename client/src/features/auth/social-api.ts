import { z } from "zod";
import {
  BackendError,
  mutationRequest,
  read,
  request,
  userSchema,
  type BackendUser,
} from "./backend-api";

const uuid = z.guid();
const relationshipsSchema = z.object({
  followingIds: z.array(uuid),
  followerIds: z.array(uuid),
  blockedIds: z.array(uuid),
});
const inviteSchema = z.object({
  id: uuid,
  tableId: uuid,
  tableName: z.string(),
  tableEmoji: z.string(),
  fromId: uuid,
  userId: uuid,
  createdAt: z.string().datetime({ offset: true }),
});

export type Relationships = z.infer<typeof relationshipsSchema>;
export type TableInvite = z.infer<typeof inviteSchema>;

function userPath(userId: string) {
  if (!uuid.safeParse(userId).success) throw new BackendError("Person not found.", 404);
  return encodeURIComponent(userId);
}

export async function getRelationships(signal?: AbortSignal): Promise<Relationships> {
  return read(
    await request("/me/relationships", signal ? { signal } : undefined),
    relationshipsSchema,
  );
}

/** Username search, excluding you, deleted users and blocks in either direction; 50 per page. */
export async function searchUsers(
  query: string,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<{ items: BackendUser[]; nextCursor: string | null }> {
  const params = new URLSearchParams({ query });
  if (cursor) params.set("cursor", cursor);
  return read(
    await request(`/users?${params}`, signal ? { signal } : undefined),
    z.object({ items: z.array(userSchema), nextCursor: z.string().nullable() }),
  );
}

export async function setFollow(userId: string, follow: boolean, key: string) {
  await mutationRequest(`/follows/${userPath(userId)}`, follow ? "POST" : "DELETE", key);
}

export async function setBlock(userId: string, block: boolean, key: string) {
  await mutationRequest(`/blocks/${userPath(userId)}`, block ? "POST" : "DELETE", key);
}

export async function listInvites(signal?: AbortSignal): Promise<TableInvite[]> {
  return read(await request("/me/invites", signal ? { signal } : undefined), z.array(inviteSchema));
}

export async function respondToInvite(inviteId: string, accept: boolean, key: string) {
  if (!uuid.safeParse(inviteId).success) throw new BackendError("Invitation not found.", 404);
  await mutationRequest(`/invites/${encodeURIComponent(inviteId)}/respond`, "POST", key, {
    accept,
  });
}

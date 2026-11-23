import { z } from "zod";
import {
  BackendError,
  mutationRequest,
  read,
  request,
  idSchema,
  idSegment,
} from "@/lib/api-client";
import { exactCents, readExact } from "@/features/settlements";

const uuid = idSchema;
const timestamp = z.string().datetime({ offset: true });
const reaction = z.object({ userId: uuid, emoji: z.string().min(1) });
const base = {
  id: uuid,
  tableId: uuid,
  createdAt: timestamp,
  reactions: z.array(reaction).optional(),
};

/** One visible chat message (backend/api/openapi.yaml ChatMessage). */
const messageSchema = z.discriminatedUnion("kind", [
  z.object({ ...base, kind: z.literal("text"), authorId: uuid, text: z.string().min(1) }),
  z.object({ ...base, kind: z.literal("gif"), authorId: uuid, gifId: z.string().min(1) }),
  z.object({
    ...base,
    kind: z.literal("bet"),
    authorId: uuid,
    betId: uuid,
    /** What the card shows without loading the bet; absent only if the bet is gone. */
    bet: z
      .object({
        question: z.string(),
        modeId: z.string(),
        status: z.enum(["ACTIVE", "PENDING", "SETTLED", "WASHED"]),
      })
      .optional(),
  }),
  z.object({ ...base, kind: z.literal("system"), text: z.string().min(1) }),
  z.object({
    ...base,
    kind: z.literal("poll"),
    authorId: uuid,
    /** The question. */
    text: z.string().min(1),
    poll: z.object({
      options: z.array(z.string().min(1)).min(2).max(6),
      /** Every vote, oldest first; one per person, and polls never close. */
      votes: z.array(z.object({ userId: uuid, option: z.number().int().min(0).max(5) })),
    }),
  }),
  z.object({
    ...base,
    kind: z.literal("settlement"),
    settlementId: uuid,
    settlementSummary: z.object({
      hostId: uuid,
      lines: z.array(z.object({ payerId: uuid, payeeId: uuid, amountCents: exactCents })),
    }),
  }),
]);

/** The latest visible message at one of your tables (backend/api/openapi.yaml ChatPreview). */
const previewSchema = z.object({
  tableId: uuid,
  message: z
    .object({
      id: uuid,
      kind: z.enum(["text", "gif", "bet", "system", "settlement", "poll"]),
      createdAt: timestamp,
      authorId: uuid.optional(),
      text: z.string().min(1).optional(),
    })
    .nullable(),
});

/** An id as a URL path segment (a malformed id is a 404 before any request). */
const tableSegment = (id: string) => idSegment(id, "Table not found.");

export type ChatPreview = z.infer<typeof previewSchema>;

/** One preview per current table, for the table cards on Home. */
export async function listLatestMessages(signal?: AbortSignal): Promise<ChatPreview[]> {
  return read(
    await request("/me/chat/latest", signal ? { signal } : undefined),
    z.array(previewSchema),
  );
}

const windowSchema = z.object({
  items: z.array(messageSchema),
  previousCursor: z.string().min(1).nullable(),
  nextCursor: z.string().min(1).nullable(),
});

const unreadSchema = z.object({
  firstUnreadMessageId: uuid.nullable(),
  unreadCount: z.number().int().nonnegative(),
});

export type ChatMessage = z.infer<typeof messageSchema>;
type ChatWindow = z.infer<typeof windowSchema>;
type ChatUnread = z.infer<typeof unreadSchema>;
/** Which window to read: the latest, older/newer than a cursor, or around a visible message. */
type WindowRequest = { latest: true } | { before: string } | { after: string } | { around: string };

export async function getChatWindow(
  tableId: string,
  window: WindowRequest,
  signal?: AbortSignal,
): Promise<ChatWindow> {
  const query =
    "before" in window
      ? `?before=${encodeURIComponent(window.before)}`
      : "after" in window
        ? `?after=${encodeURIComponent(window.after)}`
        : "around" in window
          ? `?around=${encodeURIComponent(window.around)}`
          : "";
  const result = await readExact(
    await request(
      `/chat/${tableSegment(tableId)}/messages${query}`,
      signal ? { signal } : undefined,
    ),
    windowSchema,
  );
  if (result.items.some((message) => message.tableId !== tableId))
    throw new BackendError("The backend returned an unexpected response.");
  return result;
}

export async function getChatUnread(tableId: string, signal?: AbortSignal): Promise<ChatUnread> {
  return readExact(
    await request(`/tables/${tableSegment(tableId)}/last-read`, signal ? { signal } : undefined),
    unreadSchema,
  );
}

export async function sendChatMessage(
  tableId: string,
  message: { kind: "text"; text: string } | { kind: "gif"; gifId: string },
  key: string,
): Promise<void> {
  await mutationRequest(`/tables/${tableSegment(tableId)}/messages`, "POST", key, message);
}

export async function createPoll(
  tableId: string,
  poll: { question: string; options: string[] },
  key: string,
): Promise<void> {
  await mutationRequest(`/tables/${tableSegment(tableId)}/polls`, "POST", key, poll);
}

/** Votes for one option (0-based), or removes your vote with null. */
export async function setVote(messageId: string, option: number | null, key: string) {
  if (!uuid.safeParse(messageId).success) throw new BackendError("Poll not found.", 404);
  const path = `/messages/${encodeURIComponent(messageId)}/vote`;
  if (option === null) await mutationRequest(path, "DELETE", key);
  else await mutationRequest(path, "PUT", key, { option });
}

export async function setReaction(messageId: string, emoji: string | null, key: string) {
  if (!uuid.safeParse(messageId).success) throw new BackendError("Message not found.", 404);
  const path = `/messages/${encodeURIComponent(messageId)}/reaction`;
  if (emoji === null) await mutationRequest(path, "DELETE", key);
  else await mutationRequest(path, "PUT", key, { emoji });
}

export async function markRead(tableId: string, messageId: string, key: string) {
  await mutationRequest(`/tables/${tableSegment(tableId)}/last-read`, "PUT", key, { messageId });
}

/** The chat GIF library the server accepts (`GET /gifs`). */
const catalogSchema = z.object({
  categories: z.array(z.object({ id: z.string().min(1), label: z.string().min(1) })),
  gifs: z.array(
    z.object({
      id: z.string().min(1),
      url: z.string().url(),
      alt: z.string().min(1),
      category: z.string().min(1),
      tags: z.array(z.string()),
    }),
  ),
});

export type GifCatalog = z.infer<typeof catalogSchema>;
export type Gif = GifCatalog["gifs"][number];

export async function listGifs(signal?: AbortSignal): Promise<GifCatalog> {
  return read(await request("/gifs", signal ? { signal } : undefined), catalogSchema);
}

/** Your starred GIFs, newest first (`GET /me/gif-favorites`). */
export async function listGifFavorites(signal?: AbortSignal): Promise<string[]> {
  const body = await read(
    await request("/me/gif-favorites", signal ? { signal } : undefined),
    z.object({ gifIds: z.array(z.string().min(1)) }),
  );
  return body.gifIds;
}

/** Stars or unstars a GIF. Either is safe to repeat. */
export async function setGifFavorite(gifId: string, starred: boolean, key: string) {
  await mutationRequest(
    `/me/gif-favorites/${encodeURIComponent(gifId)}`,
    starred ? "PUT" : "DELETE",
    key,
  );
}

import { z } from "zod";
import { BackendError, mutationRequest, request } from "./backend-api";
import { exactCents, readExact } from "./settlement-api";

const uuid = z.guid();
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
  z.object({ ...base, kind: z.literal("bet"), authorId: uuid, betId: uuid }),
  z.object({ ...base, kind: z.literal("system"), text: z.string().min(1) }),
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
export type ChatWindow = z.infer<typeof windowSchema>;
export type ChatUnread = z.infer<typeof unreadSchema>;
/** Which window to read: the latest, older/newer than a cursor, or around a visible message. */
export type WindowRequest =
  { latest: true } | { before: string } | { after: string } | { around: string };

function tablePath(tableId: string) {
  if (!uuid.safeParse(tableId).success) throw new BackendError("Table not found.", 404);
  return encodeURIComponent(tableId);
}

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
    await request(`/chat/${tablePath(tableId)}/messages${query}`, signal ? { signal } : undefined),
    windowSchema,
  );
  if (result.items.some((message) => message.tableId !== tableId))
    throw new BackendError("The backend returned an unexpected response.");
  return result;
}

export async function getChatUnread(tableId: string, signal?: AbortSignal): Promise<ChatUnread> {
  return readExact(
    await request(`/tables/${tablePath(tableId)}/last-read`, signal ? { signal } : undefined),
    unreadSchema,
  );
}

export async function sendChatMessage(
  tableId: string,
  message: { kind: "text"; text: string } | { kind: "gif"; gifId: string },
  key: string,
): Promise<void> {
  await mutationRequest(`/tables/${tablePath(tableId)}/messages`, "POST", key, message);
}

export async function setReaction(messageId: string, emoji: string | null, key: string) {
  if (!uuid.safeParse(messageId).success) throw new BackendError("Message not found.", 404);
  const path = `/messages/${encodeURIComponent(messageId)}/reaction`;
  if (emoji === null) await mutationRequest(path, "DELETE", key);
  else await mutationRequest(path, "PUT", key, { emoji });
}

export async function markRead(tableId: string, messageId: string, key: string) {
  await mutationRequest(`/tables/${tablePath(tableId)}/last-read`, "PUT", key, { messageId });
}

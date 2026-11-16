import { useQuery, useQueryClient } from "@tanstack/react-query";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableChatFeed } from "@/lib/realtime-connection";
import { listLatestMessages, type ChatPreview } from "./api";

export type PreviewMessage = NonNullable<ChatPreview["message"]>;

/**
 * One line for a table card describing its latest message, like "samo: calling it". `author` is the author's
 * display name, or null while it loads (text then shows without a name).
 */
export function previewText(message: PreviewMessage | null, author: string | null): string {
  if (!message) return "No messages yet";
  const who = author ?? "Someone";
  switch (message.kind) {
    case "text":
      return author ? `${author}: ${message.text ?? ""}` : (message.text ?? "");
    case "gif":
      return `${who} sent a GIF`;
    case "bet":
      return `${who} sent a ticket`;
    case "settlement":
      return "Points settled";
    case "poll":
      return `${who} started a poll: ${message.text ?? ""}`;
    case "system":
      return message.text ?? "";
  }
}

/**
 * The latest visible message at a table, from one shared `GET /me/chat/latest` query. Each caller also watches its
 * table's chat feed, so a new message anywhere refreshes the previews. `undefined` while loading or
 * unavailable.
 */
export function useChatPreview(userId: string, tableId: string): ChatPreview | undefined {
  const queryClient = useQueryClient();
  const key = userKey(userId, "chat-previews");
  useRealtimeFeed(
    tableChatFeed(tableId),
    () => void queryClient.invalidateQueries({ queryKey: key }),
  );
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => listLatestMessages(signal),
    retry: false,
  });
  if (!query.data) return undefined;
  // A table missing from a loaded list (created since the last fetch) has no messages yet.
  return query.data.find((preview) => preview.tableId === tableId) ?? { tableId, message: null };
}

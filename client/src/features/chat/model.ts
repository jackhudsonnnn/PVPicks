import { QUICK_REACTIONS, summarize, type ReactionSummary } from "@/features/table/reactions";
import type { ChatMessage } from "./chat-api";

/**
 * Pure helpers for the loaded chat history. The server orders messages by an internal sequence that clients
 * cannot see, so windows are combined by position: older windows go before, newer windows after, and a
 * refreshed window replaces the range it overlaps.
 */

export const TEXT_LIMIT = 2000;
export { QUICK_REACTIONS };

/** Appends a newer window, skipping messages already loaded. */
export function appendNewer(loaded: ChatMessage[], items: ChatMessage[]): ChatMessage[] {
  const ids = new Set(loaded.map((message) => message.id));
  const fresh = items.filter((message) => !ids.has(message.id));
  return fresh.length ? [...loaded, ...fresh] : loaded;
}

/** Prepends an older window, skipping messages already loaded. */
export function prependOlder(items: ChatMessage[], loaded: ChatMessage[]): ChatMessage[] {
  const ids = new Set(loaded.map((message) => message.id));
  const fresh = items.filter((message) => !ids.has(message.id));
  return fresh.length ? [...fresh, ...loaded] : loaded;
}

/**
 * Replaces the loaded range that a refreshed window covers, so reactions update and messages hidden since
 * (for example after a block) disappear. A window that does not overlap the loaded range is ignored: placing
 * it would leave a gap.
 */
export function mergeRange(loaded: ChatMessage[], items: ChatMessage[]): ChatMessage[] {
  if (items.length === 0) return loaded;
  const position = new Map(loaded.map((message, index) => [message.id, index]));
  const overlapping = items
    .map((message) => position.get(message.id))
    .filter((i) => i !== undefined);
  if (overlapping.length === 0) return loaded;
  const start = Math.min(...overlapping);
  const end = Math.max(...overlapping);
  const firstInWindow = position.get(items[0]!.id);
  const lastInWindow = position.get(items.at(-1)!.id);
  // Messages inside the covered range that the window no longer contains are gone.
  const before = loaded.slice(0, firstInWindow ?? start);
  const after = lastInWindow === undefined ? [] : loaded.slice(Math.max(end, lastInWindow) + 1);
  const windowIds = new Set(items.map((message) => message.id));
  return [
    ...before.filter((m) => !windowIds.has(m.id)),
    ...items,
    ...after.filter((m) => !windowIds.has(m.id)),
  ];
}

export function canReactTo(message: ChatMessage) {
  return message.kind === "text" || message.kind === "gif" || message.kind === "bet";
}

/** Reaction chips for a message, most popular first. */
export function reactionChips(message: ChatMessage, me: string): ReactionSummary[] {
  const byEmoji: Record<string, string[]> = {};
  for (const reaction of message.reactions ?? []) {
    (byEmoji[reaction.emoji] ??= []).push(reaction.userId);
  }
  return summarize(byEmoji, me);
}

/** One reaction per person: choosing your current emoji removes it, anything else replaces it. */
export function nextReaction(message: ChatMessage, me: string, emoji: string): string | null {
  const mine = message.reactions?.find((reaction) => reaction.userId === me)?.emoji;
  return mine === emoji ? null : emoji;
}

/** The text to send, or why it cannot be sent. */
export function textProblem(text: string): string | null {
  const trimmed = text.trim();
  if (!trimmed) return "Write a message first.";
  if ([...trimmed].length > TEXT_LIMIT)
    return `Keep messages to ${TEXT_LIMIT} characters or fewer.`;
  return null;
}

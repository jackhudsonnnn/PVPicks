import { QUICK_REACTIONS, summarize, type ReactionSummary } from "./reactions";
import type { ChatMessage } from "./api";

/**
 * Pure helpers for the loaded chat history. The server orders messages by an internal sequence that clients
 * cannot see, so windows are combined by position: older windows go before, newer windows after, and a
 * refreshed window replaces the range it overlaps.
 */

const TEXT_LIMIT = 2000;
export const POLL_QUESTION_LIMIT = 200;
export const POLL_OPTION_LIMIT = 80;
export const POLL_MAX_OPTIONS = 6;
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

// Settlement amounts are bigints, which JSON.stringify rejects on its own.
const bigintSafe = (_key: string, value: unknown) =>
  typeof value === "bigint" ? `${value}n` : value;

function sameMessage(a: ChatMessage, b: ChatMessage) {
  return JSON.stringify(a, bigintSafe) === JSON.stringify(b, bigintSafe);
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
  // Unchanged messages keep their objects, so their rows don't re-render.
  const kept = new Map(loaded.map((message) => [message.id, message]));
  const merged = [
    ...before.filter((m) => !windowIds.has(m.id)),
    ...items.map((m) => {
      const old = kept.get(m.id);
      return old && sameMessage(old, m) ? old : m;
    }),
    ...after.filter((m) => !windowIds.has(m.id)),
  ];
  return merged.length === loaded.length && merged.every((m, i) => m === loaded[i])
    ? loaded
    : merged;
}

export function canReactTo(message: ChatMessage) {
  return (
    message.kind === "text" ||
    message.kind === "gif" ||
    message.kind === "bet" ||
    message.kind === "poll"
  );
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

type Poll = Extract<ChatMessage, { kind: "poll" }>;

/** Each option with its voters (oldest vote first), plus the viewer's own choice. */
export function pollResults(message: Poll, me: string) {
  const options = message.poll.options.map((label) => ({ label, voters: [] as string[] }));
  for (const vote of message.poll.votes) options[vote.option]?.voters.push(vote.userId);
  const mine = message.poll.votes.find((vote) => vote.userId === me)?.option ?? null;
  return { options, total: message.poll.votes.length, mine };
}

/** Choosing your current option removes your vote; anything else sets or changes it. */
export function nextVote(message: Poll, me: string, option: number): number | null {
  return pollResults(message, me).mine === option ? null : option;
}

/** The trimmed poll to send, or why it can't be sent. Blank option rows are ignored. */
export function pollDraft(
  question: string,
  options: string[],
): { question: string; options: string[] } | { problem: string } {
  const q = question.trim();
  if (!q) return { problem: "Ask a question first." };
  if ([...q].length > POLL_QUESTION_LIMIT)
    return { problem: `Keep the question to ${POLL_QUESTION_LIMIT} characters or fewer.` };
  const filled = options.map((o) => o.trim()).filter(Boolean);
  if (filled.length < 2) return { problem: "Add at least two options." };
  if (filled.length > POLL_MAX_OPTIONS)
    return { problem: `A poll has at most ${POLL_MAX_OPTIONS} options.` };
  if (filled.some((o) => [...o].length > POLL_OPTION_LIMIT))
    return { problem: `Keep each option to ${POLL_OPTION_LIMIT} characters or fewer.` };
  if (new Set(filled.map((o) => o.toLowerCase())).size !== filled.length)
    return { problem: "Each option has to be different." };
  return { question: q, options: filled };
}

/** Emoji reactions on chat messages: messageId → emoji → userIds who reacted. */
export type Reactions = Record<string, Record<string, string[]>>;

export const QUICK_REACTIONS = ["👍", "😂", "🔥", "😭", "💰", "🤡"] as const;

/**
 * One reaction per person per message: tapping a new emoji replaces your old one,
 * tapping the same emoji again removes it.
 */
export function toggleReaction(
  all: Reactions,
  messageId: string,
  emoji: string,
  userId: string,
): Reactions {
  const had = all[messageId]?.[emoji]?.includes(userId) ?? false;
  const forMsg: Record<string, string[]> = {};
  for (const [e, ids] of Object.entries(all[messageId] ?? {})) {
    const rest = ids.filter((u) => u !== userId);
    if (rest.length) forMsg[e] = rest;
  }
  if (!had) forMsg[emoji] = [...(forMsg[emoji] ?? []), userId];
  return { ...all, [messageId]: forMsg };
}

export interface ReactionSummary {
  emoji: string;
  count: number;
  mine: boolean;
  userIds: string[];
}

/** Chips to show under a message, most popular first. */
export function summarize(
  forMsg: Record<string, string[]> | undefined,
  me: string,
): ReactionSummary[] {
  if (!forMsg) return [];
  return Object.entries(forMsg)
    .filter(([, ids]) => ids.length > 0)
    .map(([emoji, userIds]) => ({
      emoji,
      userIds,
      count: userIds.length,
      mine: userIds.includes(me),
    }))
    .sort((a, b) => b.count - a.count);
}

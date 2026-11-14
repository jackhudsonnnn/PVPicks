/** Emoji reactions on chat messages: messageId → emoji → userIds who reacted. */
export type Reactions = Record<string, Record<string, string[]>>;

export const QUICK_REACTIONS = ["👍", "😂", "🔥", "😭", "💰", "🤡"] as const;

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

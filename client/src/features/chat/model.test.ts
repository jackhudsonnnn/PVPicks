import { describe, expect, it } from "vitest";
import type { ChatMessage } from "./api";
import {
  appendNewer,
  canReactTo,
  mergeRange,
  nextReaction,
  nextVote,
  pollDraft,
  pollResults,
  prependOlder,
  reactionChips,
  textProblem,
} from "./model";

const table = "123e4567-e89b-42d3-a456-426614174000";
const me = "123e4567-e89b-42d3-a456-426614174001";
const other = "123e4567-e89b-42d3-a456-426614174002";

function text(n: number, reactions?: ChatMessage["reactions"]): ChatMessage {
  return {
    id: `123e4567-e89b-42d3-a456-4266141741${String(n).padStart(2, "0")}`,
    tableId: table,
    kind: "text",
    authorId: other,
    text: `message ${n}`,
    createdAt: "2026-11-13T12:00:00Z",
    ...(reactions ? { reactions } : {}),
  };
}

const ids = (messages: ChatMessage[]) =>
  messages.map((message) => ("text" in message ? message.text : message.kind));

describe("chat history", () => {
  it("appends newer and prepends older windows without duplicates", () => {
    const loaded = [text(3), text(4)];
    expect(ids(appendNewer(loaded, [text(4), text(5)]))).toEqual([
      "message 3",
      "message 4",
      "message 5",
    ]);
    expect(ids(prependOlder([text(1), text(2), text(3)], loaded))).toEqual([
      "message 1",
      "message 2",
      "message 3",
      "message 4",
    ]);
    expect(appendNewer(loaded, [text(4)])).toBe(loaded);
  });

  it("replaces the range a refreshed window covers", () => {
    const loaded = [text(1), text(2), text(3), text(4), text(5)];
    const updated = text(3, [{ userId: me, emoji: "🔥" }]);
    const merged = mergeRange(loaded, [text(2), updated, text(4)]);
    expect(ids(merged)).toEqual(["message 1", "message 2", "message 3", "message 4", "message 5"]);
    expect(merged[2]?.reactions).toEqual([{ userId: me, emoji: "🔥" }]);
  });

  it("drops messages that disappeared from the covered range and extends at the live end", () => {
    const loaded = [text(1), text(2), text(3)];
    expect(ids(mergeRange(loaded, [text(2), text(4)]))).toEqual([
      "message 1",
      "message 2",
      "message 4",
    ]);
  });

  it("ignores a window that does not overlap, rather than leaving a gap", () => {
    const loaded = [text(1), text(2)];
    expect(mergeRange(loaded, [text(8), text(9)])).toBe(loaded);
  });
});

describe("reactions", () => {
  it("summarizes chips and toggles one reaction per person", () => {
    const message = text(1, [
      { userId: me, emoji: "🔥" },
      { userId: other, emoji: "🔥" },
    ]);
    expect(reactionChips(message, me)).toEqual([
      { emoji: "🔥", count: 2, mine: true, userIds: [me, other] },
    ]);
    expect(nextReaction(message, me, "🔥")).toBeNull();
    expect(nextReaction(message, me, "😂")).toBe("😂");
    expect(canReactTo(message)).toBe(true);
    expect(canReactTo({ ...message, kind: "system", text: "Table created" } as ChatMessage)).toBe(
      false,
    );
  });
});

describe("text messages", () => {
  it("requires 1 to 2000 characters after trimming", () => {
    expect(textProblem("   ")).toMatch(/Write/);
    expect(textProblem(" hi ")).toBeNull();
    expect(textProblem("😀".repeat(2000))).toBeNull();
    expect(textProblem("a".repeat(2001))).toMatch(/2000/);
  });
});

describe("polls", () => {
  const me = "123e4567-e89b-42d3-a456-426614174000";
  const other = "123e4567-e89b-42d3-a456-426614174001";
  const poll = {
    id: "123e4567-e89b-42d3-a456-426614174009",
    tableId: "123e4567-e89b-42d3-a456-426614174002",
    createdAt: "2026-11-13T12:00:00Z",
    kind: "poll" as const,
    authorId: other,
    text: "Q?",
    poll: {
      options: ["A", "B", "C"],
      votes: [
        { userId: other, option: 2 },
        { userId: me, option: 0 },
      ],
    },
  };

  it("groups voters by option and finds your vote", () => {
    const results = pollResults(poll, me);
    expect(results.options.map((o) => o.voters)).toEqual([[me], [], [other]]);
    expect(results.total).toBe(2);
    expect(results.mine).toBe(0);
    expect(pollResults(poll, "someone-else").mine).toBeNull();
  });

  it("toggles your vote off when you pick it again", () => {
    expect(nextVote(poll, me, 0)).toBeNull();
    expect(nextVote(poll, me, 1)).toBe(1);
  });

  it("validates and trims a draft", () => {
    expect(pollDraft("  ", ["a", "b"])).toEqual({ problem: "Ask a question first." });
    expect(pollDraft("Q", ["a", " "])).toEqual({ problem: "Add at least two options." });
    expect(pollDraft("Q", ["Yes", "yes"])).toEqual({ problem: "Each option has to be different." });
    expect(pollDraft("Q", ["x".repeat(81), "b"])).toHaveProperty("problem");
    expect(pollDraft(" Q ", [" a ", "", "b"])).toEqual({ question: "Q", options: ["a", "b"] });
  });
});

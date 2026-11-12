import { describe, expect, it } from "vitest";
import { advanceRead, firstUnreadIndex, unreadCount } from "./unread";

const msgs = [
  { id: "1", authorId: "bo" },
  { id: "2", authorId: "me" },
  { id: "3" },
  { id: "4", authorId: "bo" },
];

describe("chat unread", () => {
  it("starts at the first message after the last one you read", () => {
    expect(firstUnreadIndex(msgs, "2", "me")).toBe(2);
    expect(unreadCount(msgs, "2", "me")).toBe(2);
  });

  it("caught up at the end", () => {
    expect(firstUnreadIndex(msgs, "4", "me")).toBe(-1);
  });

  it("your own messages are never unread", () => {
    expect(firstUnreadIndex([{ id: "a", authorId: "me" }], undefined, "me")).toBe(-1);
  });

  it("never opened means everything is unread", () => {
    expect(firstUnreadIndex(msgs, undefined, "me")).toBe(0);
  });

  it("jump to latest marks everything read and never moves back", () => {
    expect(advanceRead(msgs, "1", "4")).toBe("4");
    expect(advanceRead(msgs, "4", "2")).toBe("4");
  });
});

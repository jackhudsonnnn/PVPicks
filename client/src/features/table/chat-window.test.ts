import { describe, expect, it } from "vitest";
import { advanceRead, unreadCount } from "./unread";
import {
  CHAT_MAX_MOUNTED,
  followChatWindow,
  initialChatWindow,
  latestChatWindow,
  pageChatWindow,
} from "./chat-window";

describe("bounded chat pagination", () => {
  it("opens around the oldest unread, not thousands of unread messages", () => {
    expect(initialChatWindow(5000, 1000)).toEqual({ start: 995, end: 1025 });
    expect(initialChatWindow(5000, 0)).toEqual({ start: 0, end: 30 });
  });
  it("opens caught-up and empty chats at the latest window", () => {
    expect(initialChatWindow(5000, -1)).toEqual({ start: 4970, end: 5000 });
    expect(initialChatWindow(0, -1)).toEqual({ start: 0, end: 0 });
    expect(initialChatWindow(8, 6)).toEqual({ start: 1, end: 8 });
  });
  it("jumping to latest skips the gap and marks all messages read", () => {
    const messages = Array.from({ length: 5000 }, (_, i) => ({ id: String(i), authorId: "peer" }));
    const latest = latestChatWindow(messages.length);
    expect(latest).toEqual({ start: 4970, end: 5000 });
    const read = advanceRead(messages, "999", "4999");
    expect(unreadCount(messages, read, "me")).toBe(0);
  });
  it("prunes the opposite end while paging in either direction", () => {
    expect(pageChatWindow({ start: 995, end: 1025 }, 5000, "newer")).toEqual({
      start: 995,
      end: 1050,
    });
    expect(pageChatWindow({ start: 995, end: 1050 }, 5000, "newer")).toEqual({
      start: 1015,
      end: 1075,
    });
    expect(pageChatWindow({ start: 1015, end: 1075 }, 5000, "older")).toEqual({
      start: 990,
      end: 1050,
    });
  });
  it("can traverse thousands of messages both ways without exceeding 60 mounted rows", () => {
    let window = initialChatWindow(5000, 1000);
    for (let i = 0; i < 220; i++) {
      window = pageChatWindow(window, 5000, "newer");
      expect(window.end - window.start).toBeLessThanOrEqual(CHAT_MAX_MOUNTED);
    }
    expect(window.end).toBe(5000);
    for (let i = 0; i < 220; i++) {
      window = pageChatWindow(window, 5000, "older");
      expect(window.end - window.start).toBeLessThanOrEqual(60);
    }
    expect(window.start).toBe(0);
  });
  it("bounds new arrivals and never pages outside history", () => {
    expect(followChatWindow({ start: 0, end: 30 }, 5000)).toEqual({ start: 4940, end: 5000 });
    expect(pageChatWindow({ start: 0, end: 8 }, 8, "older")).toEqual({ start: 0, end: 8 });
    expect(pageChatWindow({ start: 0, end: 8 }, 8, "newer")).toEqual({ start: 0, end: 8 });
  });
});

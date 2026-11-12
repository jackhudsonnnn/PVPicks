import { describe, expect, it } from "vitest";
import { markAllRead, pageAfter, pushItem, unreadCount, type NotificationItem } from "./history";

const item = (n: number, read = false): NotificationItem => ({
  id: `n${n}`,
  tone: "chat",
  title: `#${n}`,
  at: n,
  read,
});

describe("notification history", () => {
  it("adds newest first and drops the oldest past the cap", () => {
    let items: NotificationItem[] = [];
    for (let i = 1; i <= 5; i++) items = pushItem(items, item(i), 3);
    expect(items.map((i) => i.id)).toEqual(["n5", "n4", "n3"]);
  });

  it("pages with a cursor, and new items at the top don't shift later pages", () => {
    let items: NotificationItem[] = [];
    for (let i = 1; i <= 5; i++) items = pushItem(items, item(i));
    const first = pageAfter(items, null, 2);
    expect(first.items.map((i) => i.id)).toEqual(["n5", "n4"]);
    items = pushItem(items, item(6));
    const second = pageAfter(items, first.nextCursor, 2);
    expect(second.items.map((i) => i.id)).toEqual(["n3", "n2"]);
    const last = pageAfter(items, second.nextCursor, 2);
    expect(last.items.map((i) => i.id)).toEqual(["n1"]);
    expect(last.nextCursor).toBeNull();
  });

  it("counts and clears unread", () => {
    const items = [item(1), item(2, true), item(3)];
    expect(unreadCount(items)).toBe(2);
    expect(unreadCount(markAllRead(items))).toBe(0);
  });
});

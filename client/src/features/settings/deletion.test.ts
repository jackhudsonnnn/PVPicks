import { describe, expect, it } from "vitest";
import type { Bet } from "@/features/bets/types";
import type { Message, Table } from "@/features/table/types";
import type { User } from "@/features/social/types";
import { DELETED_NAME, deleteUser } from "./deletion";

const users: User[] = [
  { id: "a", username: "ann", hue: 1 },
  { id: "b", username: "ben", hue: 2 },
  { id: "c", username: "cy", hue: 3 },
];
const table: Table = {
  id: "t",
  name: "T",
  emoji: "🏈",
  memberIds: ["a", "b", "c"],
  hostIds: ["a"],
};
const bet = (id: string, status: Bet["status"], extra: Partial<Bet> = {}): Bet => ({
  id,
  tableId: "t",
  proposerId: "a",
  modeId: "either_or",
  config: {},
  question: "?",
  choices: ["X", "Y"],
  wager: 1,
  durationSec: 60,
  closesInSec: 0,
  status,
  createdAt: "now",
  participants: [
    { userId: "a", selection: "X" },
    { userId: "b", selection: "Y" },
  ],
  ...extra,
});
const messages: Message[] = [
  { id: "m1", tableId: "t", kind: "text", authorId: "a", text: "hi", at: "1" },
  { id: "m2", tableId: "t", kind: "text", authorId: "b", text: "yo", at: "1" },
  { id: "m3", tableId: "t", kind: "bet", authorId: "a", betId: "old", at: "1" },
];
const state = {
  users,
  tables: [table],
  bets: [bet("old", "resolved", { deltas: { a: 1, b: -1 } }), bet("live", "active")],
  messages,
  follows: { a: ["b"], b: ["a", "c"] },
  tableInvites: [{ id: "i", tableId: "t", fromId: "a", userId: "x" }],
  reactions: { m2: { "😂": ["a", "c"] } },
  blocks: { a: ["c"], c: ["a"] },
};

describe("deleting an account", () => {
  const next = deleteUser(state, "a");

  it("keeps past results so the ledger still adds up, under 'Deleted user'", () => {
    const old = next.bets.find((b) => b.id === "old")!;
    expect(old.deltas).toEqual({ a: 1, b: -1 });
    expect(next.users.find((u) => u.id === "a")).toMatchObject({
      username: DELETED_NAME,
      deleted: true,
    });
    expect(next.messages.some((m) => m.id === "m3")).toBe(true);
  });

  it("drops their picks on open tickets and their chats, follows, reactions and blocks", () => {
    expect(next.bets.find((b) => b.id === "live")!.participants.map((p) => p.userId)).toEqual([
      "b",
    ]);
    expect(next.messages.some((m) => m.id === "m1")).toBe(false);
    expect(next.follows).toEqual({ b: ["c"] });
    expect(next.reactions).toEqual({ m2: { "😂": ["c"] } });
    expect(next.blocks).toEqual({ c: [] });
    expect(next.tableInvites).toEqual([]);
  });

  it("removes them from tables and hands hosting to the next member", () => {
    expect(next.tables[0]).toMatchObject({ memberIds: ["b", "c"], hostIds: ["b"] });
  });
});

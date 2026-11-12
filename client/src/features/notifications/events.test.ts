import { describe, expect, it } from "vitest";
import type { Bet } from "@/features/bets/types";
import type { Message } from "@/features/table/types";
import { DEFAULT_PREFS, diffEvents, shouldNotify, type NotificationEvent } from "./events";

const ME = "me";
const bet = (over: Partial<Bet> = {}): Bet => ({
  id: "b1",
  tableId: "t1",
  proposerId: "u_other",
  modeId: "either_or",
  gameId: "g1",
  config: {},
  question: "Q?",
  choices: ["A", "B"],
  wager: 2,
  durationSec: 30,
  closesInSec: 10,
  status: "active",
  participants: [{ userId: ME, selection: "A" }],
  createdAt: "1:00 PM",
  ...over,
});
const text = (over: Partial<Message> = {}): Message =>
  ({
    id: "m1",
    tableId: "t1",
    kind: "text",
    authorId: "u_other",
    text: "hi",
    at: "1:00 PM",
    ...over,
  }) as Message;

describe("diffEvents", () => {
  it("reports a ticket locking from Active to Pending", () => {
    const ev = diffEvents(
      { bets: [bet()], messages: [] },
      { bets: [bet({ status: "pending" })], messages: [] },
      ME,
    );
    expect(ev).toMatchObject([{ kind: "ticket", from: "active", to: "pending" }]);
  });

  it("ignores countdown ticks that don't change status", () => {
    const ev = diffEvents(
      { bets: [bet()], messages: [] },
      { bets: [bet({ closesInSec: 9 })], messages: [] },
      ME,
    );
    expect(ev).toEqual([]);
  });

  it("skips my own new ticket but reports someone else's", () => {
    const mine = diffEvents(
      { bets: [], messages: [] },
      { bets: [bet({ proposerId: ME })], messages: [] },
      ME,
    );
    const theirs = diffEvents({ bets: [], messages: [] }, { bets: [bet()], messages: [] }, ME);
    expect(mine).toEqual([]);
    expect(theirs).toHaveLength(1);
  });

  it("reports new chat from others, not from me", () => {
    const ev = diffEvents(
      { bets: [], messages: [] },
      { bets: [], messages: [text(), text({ id: "m2", authorId: ME })] },
      ME,
    );
    expect(ev).toHaveLength(1);
  });
});

describe("shouldNotify", () => {
  const ctx = { me: ME };
  const locked: NotificationEvent = {
    kind: "ticket",
    bet: bet({ status: "pending" }),
    from: "active",
    to: "pending",
  };
  const notMine: NotificationEvent = {
    ...locked,
    bet: bet({ status: "pending", participants: [] }),
  } as NotificationEvent;
  const chat = { kind: "chat", message: text() } as NotificationEvent;

  it("master switch off silences everything", () => {
    const prefs = { ...DEFAULT_PREFS, enabled: false };
    expect(shouldNotify({ kind: "error", title: "x" }, prefs, ctx)).toBe(false);
    expect(shouldNotify(locked, prefs, ctx)).toBe(false);
  });

  it("'my tickets' scope skips tickets I'm not in; 'all' includes them", () => {
    expect(shouldNotify(notMine, DEFAULT_PREFS, ctx)).toBe(false);
    expect(shouldNotify(notMine, { ...DEFAULT_PREFS, ticketScope: "all" }, ctx)).toBe(true);
    expect(shouldNotify(locked, DEFAULT_PREFS, ctx)).toBe(true);
  });

  it("muted tables and the table you're viewing don't toast chats", () => {
    expect(shouldNotify(chat, DEFAULT_PREFS, ctx)).toBe(true);
    expect(shouldNotify(chat, { ...DEFAULT_PREFS, mutedTableIds: ["t1"] }, ctx)).toBe(false);
    expect(shouldNotify(chat, DEFAULT_PREFS, { me: ME, viewingTableId: "t1" })).toBe(false);
  });

  it("chats from people you blocked never notify", () => {
    const author = (chat as Extract<NotificationEvent, { kind: "chat" }>).message.authorId;
    expect(shouldNotify(chat, DEFAULT_PREFS, { me: ME, blocked: [author] })).toBe(false);
  });

  it("error toggle controls error toasts", () => {
    expect(
      shouldNotify({ kind: "error", title: "x" }, { ...DEFAULT_PREFS, errors: false }, ctx),
    ).toBe(false);
  });
});

describe("table invite notifications", () => {
  const tbl = { id: "t9", name: "Hoops", emoji: "🏀", memberIds: ["h"], hostIds: ["h"] };
  const base = { bets: [], messages: [] };

  it("a new request for me becomes an invite event", () => {
    const inv = { id: "i1", tableId: "t9", fromId: "h", userId: ME };
    const ev = diffEvents(
      { ...base, tables: [], invites: [] },
      { ...base, tables: [tbl], invites: [inv] },
      ME,
    );
    expect(ev).toEqual([
      { kind: "invite", tableId: "t9", tableName: "Hoops", fromId: "h", joined: false },
    ]);
  });

  it("being added straight in is a joined invite; accepting my own request is not", () => {
    const inMe = { ...tbl, memberIds: ["h", ME] };
    expect(
      diffEvents({ ...base, tables: [], invites: [] }, { ...base, tables: [inMe] }, ME),
    ).toEqual([{ kind: "invite", tableId: "t9", tableName: "Hoops", fromId: "h", joined: true }]);
    const inv = { id: "i1", tableId: "t9", fromId: "h", userId: ME };
    expect(
      diffEvents(
        { ...base, tables: [tbl], invites: [inv] },
        { ...base, tables: [inMe], invites: [] },
        ME,
      ),
    ).toEqual([]);
  });

  it("the Table invites switch controls them", () => {
    const e = {
      kind: "invite",
      tableId: "t9",
      tableName: "H",
      fromId: "h",
      joined: false,
    } as const;
    expect(shouldNotify(e, DEFAULT_PREFS, { me: ME })).toBe(true);
    expect(shouldNotify(e, { ...DEFAULT_PREFS, invites: false }, { me: ME })).toBe(false);
  });
});

describe("settle-up forgive notifications", () => {
  it("notifies both people once when a pending $5 payment becomes $8, but not outsiders", () => {
    const sent: Message = { id: "s", kind: "settlement", tableId: "t", hostId: "a", lines: [{ userId: "b", amount: -5 }], hostNet: 5, at: "now", payments: { b: "sent" } };
    const extra: Message = { ...sent, id: "s2", lines: [{ userId: "b", amount: -3 }], hostNet: 3, payments: {} };
    const prev = { bets: [], messages: [sent] };
    const next = { bets: [], messages: [{ ...sent, payments: { b: "unpaid" as const } }, extra] };
    expect(diffEvents(prev, next, "a")).toEqual([{ kind: "balanceChanged", otherId: "b", before: 5, after: 8 }]);
    expect(diffEvents(prev, next, "b")).toEqual([{ kind: "balanceChanged", otherId: "a", before: -5, after: -8 }]);
    expect(diffEvents(prev, next, "outside")).toEqual([]);
  });
  const settle = (extra: Record<string, unknown> = {}) =>
    ({
      id: "s1",
      tableId: "t1",
      kind: "settlement",
      hostId: ME,
      hostNet: 5,
      at: "now",
      lines: [{ userId: "u_x", amount: -5 }],
      ...extra,
    }) as never;

  it("tells you when someone asks you to forgive what they owe", () => {
    const asked = settle({ forgiveAsks: { u_x: { status: "open", at: 1 } } });
    const ev = diffEvents({ bets: [], messages: [settle()] }, { bets: [], messages: [asked] }, ME);
    expect(ev).toEqual([
      { kind: "settle", change: "asked", tableId: "t1", otherId: "u_x", amount: 5 },
    ]);
  });

  it("tells the payer when they're forgiven", () => {
    const owe = settle({ hostId: "u_x", lines: [{ userId: ME, amount: -5 }] });
    const done = { ...(owe as object), payments: { [ME]: "forgiven" } } as never;
    const ev = diffEvents({ bets: [], messages: [owe] }, { bets: [], messages: [done] }, ME);
    expect(ev).toMatchObject([{ change: "forgiven", otherId: "u_x" }]);
  });
});

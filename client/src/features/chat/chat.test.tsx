import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile } from "@/features/people/api";
import type { Table } from "@/features/tables/api";
import { TableChat } from "./chat";
import {
  createPoll,
  getChatUnread,
  getChatWindow,
  markRead,
  sendChatMessage,
  setReaction,
  setVote,
  type ChatMessage,
} from "./api";
import type { Invalidation } from "@/lib/realtime-connection";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  listGifs: vi.fn(async () => (await import("@/test/api-fixtures")).gifCatalog),
  getChatWindow: vi.fn(),
  getChatUnread: vi.fn(),
  markRead: vi.fn(),
  sendChatMessage: vi.fn(),
  setReaction: vi.fn(),
  createPoll: vi.fn(),
  setVote: vi.fn(),
}));
const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("@/lib/realtime", () => ({
  useRealtimeStatus: () => null,
  useRealtimeFeed: (destination: string, listener: (event: Invalidation) => void) => {
    feeds.set(destination, listener);
  },
}));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const table: Table = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  name: "Table",
  emoji: "T",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  hostsOnlyBets: false,
  u2pickEnabled: true,
  pollsEnabled: true,
  jackblackAccess: "everyone" as const,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
  disabledPermissions: {},
};

function message(n: number, extra: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: `123e4567-e89b-42d3-a456-4266141742${String(n).padStart(2, "0")}`,
    tableId: table.id,
    kind: "text",
    authorId: friend,
    text: `hello ${n}`,
    createdAt: "2026-11-13T12:00:00Z",
    ...extra,
  } as ChatMessage;
}

const expire = vi.fn();

function renderChat(override: Partial<Table> = {}) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <TableChat user={me} table={{ ...table, ...override }} expire={expire} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  feeds.clear();
  vi.resetAllMocks();
  vi.mocked(getProfile).mockResolvedValue({ id: friend, username: "friend", hue: 2 });
  vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
  vi.mocked(getChatWindow).mockResolvedValue({
    items: [message(1), message(2)],
    previousCursor: null,
    nextCursor: "after-2",
  });
  vi.mocked(markRead).mockResolvedValue();
  vi.mocked(sendChatMessage).mockResolvedValue();
  vi.mocked(setReaction).mockResolvedValue();
  vi.mocked(createPoll).mockResolvedValue();
  vi.mocked(setVote).mockResolvedValue();
});

describe("table chat", () => {
  it("opens at the first unread message, marks it, loads newer and records the read position", async () => {
    vi.mocked(getChatUnread).mockResolvedValue({
      firstUnreadMessageId: message(2).id,
      unreadCount: 2,
    });
    vi.mocked(getChatWindow).mockImplementation(async (_table, window) =>
      "around" in window
        ? { items: [message(1), message(2)], previousCursor: "before-1", nextCursor: "after-2" }
        : { items: [message(3)], previousCursor: null, nextCursor: "after-3" },
    );
    renderChat();
    expect(await screen.findByText("hello 3")).toBeInTheDocument();
    expect(screen.getByText("New messages")).toBeInTheDocument();
    expect(getChatWindow).toHaveBeenCalledWith(table.id, { around: message(2).id });
    expect(getChatWindow).toHaveBeenCalledWith(table.id, { after: "after-2" });
    await waitFor(() =>
      expect(markRead).toHaveBeenCalledWith(table.id, message(3).id, expect.any(String)),
    );
    expect(await screen.findAllByText(/friend/)).not.toHaveLength(0);
  });

  it("doesn't page through a big unread backlog: one newer page, then Load newer or Jump to latest", async () => {
    const page = (from: number) => Array.from({ length: 25 }, (_, i) => message(from + i));
    vi.mocked(getChatUnread).mockResolvedValue({
      firstUnreadMessageId: message(2).id,
      unreadCount: 900,
    });
    vi.mocked(getChatWindow).mockImplementation(async (_table, window) =>
      "around" in window
        ? { items: [message(1), message(2)], previousCursor: null, nextCursor: "after-2" }
        : "after" in window
          ? window.after === "after-2"
            ? { items: page(10), previousCursor: null, nextCursor: "after-34" }
            : { items: page(40), previousCursor: null, nextCursor: "after-64" }
          : {
              items: [message(98), message(99)],
              previousCursor: "before-98",
              nextCursor: "after-99",
            },
    );
    renderChat();
    expect(await screen.findByText("hello 34")).toBeInTheDocument();
    expect(getChatWindow).toHaveBeenCalledTimes(2);
    expect(markRead).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Load newer messages" }));
    expect(await screen.findByText("hello 64")).toBeInTheDocument();
    expect(getChatWindow).toHaveBeenLastCalledWith(table.id, { after: "after-34" });

    fireEvent.click(screen.getByRole("button", { name: /Jump to latest/ }));
    expect(await screen.findByText("hello 99")).toBeInTheDocument();
    expect(getChatWindow).toHaveBeenLastCalledWith(table.id, { latest: true });
    expect(screen.queryByText("hello 34")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load newer messages" })).not.toBeInTheDocument();
    await waitFor(() =>
      expect(markRead).toHaveBeenCalledWith(table.id, message(99).id, expect.any(String)),
    );
  });

  it("loads older history above the loaded messages", async () => {
    vi.mocked(getChatWindow).mockImplementation(async (_table, window) =>
      "before" in window
        ? { items: [message(0)], previousCursor: null, nextCursor: "after-0" }
        : { items: [message(1), message(2)], previousCursor: "before-1", nextCursor: "after-2" },
    );
    renderChat();
    fireEvent.click(await screen.findByRole("button", { name: "Load older messages" }));
    expect(await screen.findByText("hello 0")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load older messages" })).not.toBeInTheDocument();
  });

  it("sends trimmed text and GIFs with a retry key, then loads arrivals", async () => {
    renderChat();
    await screen.findByText("hello 2");
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Write a message");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "  hi all  " } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() =>
      expect(sendChatMessage).toHaveBeenCalledWith(
        table.id,
        { kind: "text", text: "hi all" },
        expect.any(String),
      ),
    );
    await waitFor(() => expect(getChatWindow).toHaveBeenCalledWith(table.id, { after: "after-2" }));
    fireEvent.click(screen.getByRole("button", { name: "Choose a GIF" }));
    fireEvent.click(screen.getAllByRole("button", { name: /Send GIF/ })[0]!);
    await waitFor(() =>
      expect(sendChatMessage).toHaveBeenLastCalledWith(
        table.id,
        { kind: "gif", gifId: expect.stringMatching(/^g\d+$/) },
        expect.any(String),
      ),
    );
  });

  it("keeps a refused message in the box to edit, and clears a sent one", async () => {
    const { BackendError } = await import("@/lib/api-client");
    vi.mocked(sendChatMessage).mockRejectedValueOnce(
      new BackendError(
        "Backend request failed.",
        400,
        "abusive_language",
        "That has language that isn't allowed here.",
      ),
    );
    renderChat();
    await screen.findByText("hello 2");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "something nasty" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(sendChatMessage).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByLabelText("Message")).toBeEnabled());
    expect(screen.getByLabelText("Message")).toHaveValue("something nasty");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "something nice" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(sendChatMessage).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByLabelText("Message")).toHaveValue(""));
  });

  it("keeps an uncertain send and retries it with the same key", async () => {
    vi.mocked(sendChatMessage).mockRejectedValueOnce(new TypeError("offline"));
    renderChat();
    await screen.findByText("hello 2");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "hi" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText(/may not have been sent/)).toBeInTheDocument();
    expect(screen.getByLabelText("Message")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retry same action" }));
    await waitFor(() => expect(sendChatMessage).toHaveBeenCalledTimes(2));
    const keys = vi.mocked(sendChatMessage).mock.calls.map((call) => call[2]);
    expect(keys[1]).toBe(keys[0]);
  });

  it("toggles the viewer's reaction and refreshes that message", async () => {
    vi.mocked(getChatWindow).mockResolvedValue({
      items: [message(1, { reactions: [{ userId: me.id, emoji: "🔥" }] })],
      previousCursor: null,
      nextCursor: "after-1",
    });
    renderChat();
    fireEvent.click(await screen.findByRole("button", { name: "🔥 1, including you" }));
    await waitFor(() =>
      expect(setReaction).toHaveBeenCalledWith(message(1).id, null, expect.any(String)),
    );
    await waitFor(() =>
      expect(getChatWindow).toHaveBeenCalledWith(table.id, { around: message(1).id }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Add a reaction" }));
    fireEvent.click(screen.getByRole("button", { name: "React 😂" }));
    await waitFor(() =>
      expect(setReaction).toHaveBeenLastCalledWith(message(1).id, "😂", expect.any(String)),
    );
  });

  it("shows a new reaction without a reload", async () => {
    vi.mocked(getChatWindow)
      .mockResolvedValueOnce({ items: [message(1)], previousCursor: null, nextCursor: "after-1" })
      .mockResolvedValue({
        items: [message(1, { reactions: [{ userId: me.id, emoji: "😂" }] })],
        previousCursor: null,
        nextCursor: "after-1",
      });
    renderChat();
    fireEvent.click(await screen.findByRole("button", { name: "Add a reaction" }));
    fireEvent.click(screen.getByRole("button", { name: "React 😂" }));
    expect(await screen.findByRole("button", { name: "😂 1, including you" })).toBeInTheDocument();
  });

  it("respects disabled text, GIF and reaction permissions", async () => {
    renderChat({ disabledPermissions: { [me.id]: ["text", "react"] } });
    await screen.findByText("hello 2");
    expect(screen.getByLabelText("Message")).toBeDisabled();
    expect(screen.getByRole("button", { name: "Choose a GIF" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "Add a reaction" })).not.toBeInTheDocument();
  });

  it("renders bet, system and settlement messages", async () => {
    vi.mocked(getChatWindow).mockResolvedValue({
      items: [
        message(1, { kind: "system", text: "Table created", authorId: undefined } as never),
        message(2, {
          kind: "bet",
          betId: table.id,
          text: undefined,
          bet: { question: "Who wins?", modeId: "u2pick", status: "ACTIVE" },
        } as never),
        {
          id: message(3).id,
          tableId: table.id,
          kind: "settlement",
          createdAt: "2026-11-13T12:00:00Z",
          settlementId: table.id,
          settlementSummary: {
            hostId: me.id,
            lines: [{ payerId: friend, payeeId: me.id, amountCents: 9007199254740993n }],
          },
        },
      ],
      previousCursor: null,
      nextCursor: "after-3",
    });
    renderChat();
    expect(await screen.findByText(/Table created/)).toBeInTheDocument();
    expect(screen.getByText("Who wins?")).toBeInTheDocument();
    expect(await screen.findByText(/90,071,992,547,409\.93 points/)).toBeInTheDocument();
  });

  it("shows the first message in a chat that started empty", async () => {
    vi.mocked(getChatWindow).mockResolvedValueOnce({
      items: [],
      previousCursor: null,
      nextCursor: null,
    });
    renderChat();
    expect(await screen.findByText(/No messages yet/)).toBeInTheDocument();
    vi.mocked(getChatWindow).mockResolvedValue({
      items: [message(1)],
      previousCursor: null,
      nextCursor: "after-1",
    });
    act(() =>
      feeds.get(`/user/queue/tables/${table.id}/chat`)?.({
        eventId: "123e4567-e89b-42d3-a456-426614174098",
        type: "chat.changed",
        schemaVersion: 1,
        occurredAt: "2026-11-13T12:00:00Z",
        tableId: table.id,
      }),
    );
    expect(await screen.findByText("hello 1")).toBeInTheDocument();
  });

  it("loads arrivals when the chat feed says it changed", async () => {
    renderChat();
    await screen.findByText("hello 2");
    vi.mocked(getChatWindow).mockImplementation(async (_table, window) =>
      "after" in window
        ? { items: [message(3)], previousCursor: null, nextCursor: "after-3" }
        : {
            items: [message(1), message(2), message(3)],
            previousCursor: null,
            nextCursor: "after-3",
          },
    );
    const listener = feeds.get(`/user/queue/tables/${table.id}/chat`);
    act(() =>
      listener?.({
        eventId: "123e4567-e89b-42d3-a456-426614174099",
        type: "chat.changed",
        schemaVersion: 1,
        occurredAt: "2026-11-13T12:00:00Z",
        tableId: table.id,
      }),
    );
    expect(await screen.findByText("hello 3")).toBeInTheDocument();
  });

  it("folds a burst of changes into one refresh, and opens the latest window when far behind", async () => {
    renderChat();
    await screen.findByText("hello 2");
    const page = (from: number) => Array.from({ length: 25 }, (_, i) => message(from + i));
    let release!: () => void;
    const held = new Promise<void>((resolve) => (release = resolve));
    vi.mocked(getChatWindow).mockClear();
    vi.mocked(getChatWindow).mockImplementation(async (_table, window) => {
      await held;
      return "after" in window
        ? {
            items: page(window.after === "after-2" ? 10 : 40),
            previousCursor: null,
            nextCursor: `next-${window.after}`,
          }
        : {
            items: [message(98), message(99)],
            previousCursor: "before-98",
            nextCursor: "after-99",
          };
    });
    const listener = feeds.get(`/user/queue/tables/${table.id}/chat`);
    const event = {
      eventId: "123e4567-e89b-42d3-a456-426614174099",
      type: "chat.changed",
      schemaVersion: 1,
      occurredAt: "2026-11-13T12:00:00Z",
      tableId: table.id,
    } as const;
    act(() => {
      for (let i = 0; i < 10; i++) listener?.(event);
    });
    release();
    expect(await screen.findByText("hello 99")).toBeInTheDocument();
    // Two refreshes at most (one running, one queued), each two newer pages then the latest window.
    await waitFor(() => expect(screen.queryByText("hello 10")).not.toBeInTheDocument());
    expect(vi.mocked(getChatWindow).mock.calls.length).toBeLessThanOrEqual(6);
  });

  describe("polls", () => {
    const poll = message(5, {
      kind: "poll",
      text: "Pizza or tacos?",
      poll: { options: ["Pizza", "Tacos"], votes: [{ userId: friend, option: 1 }] },
    } as Partial<ChatMessage>);

    it("shows the tally and voters, and votes, changes and removes your vote", async () => {
      vi.mocked(getChatWindow).mockResolvedValue({
        items: [poll],
        previousCursor: null,
        nextCursor: "after-5",
      });
      renderChat();
      const tacos = await screen.findByRole("button", { name: "Tacos: 1 vote" });
      expect(tacos).toBeInTheDocument();
      fireEvent.click(screen.getByRole("button", { name: "Pizza: 0 votes" }));
      await waitFor(() => expect(setVote).toHaveBeenCalledWith(poll.id, 0, expect.any(String)));

      vi.mocked(getChatWindow).mockResolvedValue({
        items: [
          message(5, {
            ...poll,
            poll: {
              options: ["Pizza", "Tacos"],
              votes: [
                { userId: friend, option: 1 },
                { userId: me.id, option: 0 },
              ],
            },
          } as Partial<ChatMessage>),
        ],
        previousCursor: null,
        nextCursor: "after-5",
      });
      act(() => feeds.get(`/user/queue/tables/${table.id}/chat`)?.({} as Invalidation));
      const mine = await screen.findByRole("button", { name: "Pizza: 1 vote, your vote" });
      fireEvent.click(mine);
      await waitFor(() => expect(setVote).toHaveBeenCalledWith(poll.id, null, expect.any(String)));
    });

    it("starts a poll from the composer, and hides the button when polls are off or not allowed", async () => {
      const { unmount } = renderChat();
      fireEvent.click(await screen.findByRole("button", { name: "Start a poll" }));
      fireEvent.change(screen.getByRole("textbox", { name: "Question" }), {
        target: { value: " Where next? " },
      });
      fireEvent.change(screen.getByRole("textbox", { name: "Option 1" }), {
        target: { value: "Bar" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Post poll" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("at least two options");
      fireEvent.change(screen.getByRole("textbox", { name: "Option 2" }), {
        target: { value: "bar " },
      });
      fireEvent.click(screen.getByRole("button", { name: "Post poll" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("different");
      fireEvent.click(screen.getByRole("button", { name: /Add option/ }));
      fireEvent.change(screen.getByRole("textbox", { name: "Option 2" }), {
        target: { value: "Diner" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Post poll" }));
      await waitFor(() =>
        expect(createPoll).toHaveBeenCalledWith(
          table.id,
          { question: "Where next?", options: ["Bar", "Diner"] },
          expect.any(String),
        ),
      );
      // Posting the poll doesn't also submit the (empty) message box.
      expect(screen.queryByText("Write a message first.")).not.toBeInTheDocument();
      unmount();

      const off = renderChat({ pollsEnabled: false });
      await screen.findByText("hello 1");
      expect(screen.queryByRole("button", { name: "Start a poll" })).not.toBeInTheDocument();
      off.unmount();
      renderChat({ disabledPermissions: { [me.id]: ["polls"] } });
      await screen.findByText("hello 1");
      expect(screen.queryByRole("button", { name: "Start a poll" })).not.toBeInTheDocument();
    });
  });
});

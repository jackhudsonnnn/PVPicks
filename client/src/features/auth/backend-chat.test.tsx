import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile, type BackendTable } from "./backend-api";
import { BackendChat } from "./backend-chat";
import {
  getChatUnread,
  getChatWindow,
  markRead,
  sendChatMessage,
  setReaction,
  type ChatMessage,
} from "./chat-api";
import type { Invalidation } from "./realtime-connection";

vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./chat-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-api")>()),
  getChatWindow: vi.fn(),
  getChatUnread: vi.fn(),
  markRead: vi.fn(),
  sendChatMessage: vi.fn(),
  setReaction: vi.fn(),
}));
const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("./realtime", () => ({
  useRealtimeStatus: () => null,
  useRealtimeFeed: (destination: string, listener: (event: Invalidation) => void) => {
    feeds.set(destination, listener);
  },
}));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const table: BackendTable = {
  id: "123e4567-e89b-42d3-a456-426614174002",
  name: "Table",
  emoji: "T",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
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

function renderChat(override: Partial<BackendTable> = {}) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <BackendChat
        user={me}
        table={{ ...table, ...override }}
        expire={expire}
        betQuestion={() => "Who wins?"}
        onRead={() => {}}
      />
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
    expect(screen.getByText("New")).toBeInTheDocument();
    expect(getChatWindow).toHaveBeenCalledWith(table.id, { around: message(2).id });
    expect(getChatWindow).toHaveBeenCalledWith(table.id, { after: "after-2" });
    await waitFor(() =>
      expect(markRead).toHaveBeenCalledWith(table.id, message(3).id, expect.any(String)),
    );
    expect(await screen.findAllByText(/friend/)).not.toHaveLength(0);
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

  it("keeps an uncertain send and retries it with the same key", async () => {
    vi.mocked(sendChatMessage).mockRejectedValueOnce(new TypeError("offline"));
    renderChat();
    await screen.findByText("hello 2");
    fireEvent.change(screen.getByLabelText("Message"), { target: { value: "hi" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText(/may not have been sent/)).toBeInTheDocument();
    expect(screen.getByLabelText("Message")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
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
        message(2, { kind: "bet", betId: table.id, text: undefined } as never),
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
    expect(screen.getByText("Proposed a bet: Who wins?")).toBeInTheDocument();
    expect(await screen.findByText(/90,071,992,547,409\.93 points/)).toBeInTheDocument();
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
});

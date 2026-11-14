import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile, type BackendTable } from "./backend-api";
import { BackendChat } from "./backend-chat";
import { HostPanel } from "./backend-host-panel";
import { ProfileDialog } from "./backend-profile";
import { TomatoLayer } from "./backend-tomatoes";
import { useBetActions } from "./bet-actions";
import { getChatUnread, getChatWindow, markRead } from "./chat-api";
import type { Invalidation } from "./realtime-connection";
import { listSettlementGroups } from "./settlement-api";
import { useSocialActions } from "./social-actions";
import { getRelationships, submitReport } from "./social-api";
import { useTableActions } from "./table-actions";
import {
  listTomatoes,
  rotateQuickPickCode,
  setQuickPicksEnabled,
  throwTomato,
  type TomatoThrow,
} from "./tables-api";

vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./chat-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./chat-api")>()),
  getChatUnread: vi.fn(),
  getChatWindow: vi.fn(),
  markRead: vi.fn(),
}));
vi.mock("./social-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./social-api")>()),
  getRelationships: vi.fn(),
  submitReport: vi.fn(),
}));
vi.mock("./settlement-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./settlement-api")>()),
  listSettlementGroups: vi.fn(),
}));
vi.mock("./tables-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./tables-api")>()),
  listTomatoes: vi.fn(),
  throwTomato: vi.fn(),
  setQuickPicksEnabled: vi.fn(),
  rotateQuickPickCode: vi.fn(),
}));
const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("./realtime", () => ({
  useRealtimeStatus: () => "live",
  useRealtimeFeed: (destination: string | null, listener: (event: Invalidation) => void) => {
    if (destination) feeds.set(destination, listener);
  },
}));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const table: BackendTable = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  name: "Game Night",
  emoji: "🎲",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
  quickPicksEnabled: true,
  quickPickSharing: true,
  quickPickCode: "K4R7QX",
  subscribedTo: [],
  disabledPermissions: {},
};
const expire = vi.fn();
const peopleKey = ["backend-tables", me.id, "people"];

function wrap(children: ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      {children}
    </QueryClientProvider>,
  );
}

function Chat() {
  const social = useSocialActions(me.id, async () => {}, expire);
  return (
    <BackendChat
      user={me}
      table={table}
      expire={expire}
      betQuestion={() => undefined}
      social={social}
      onRead={() => {}}
    />
  );
}

function Profile({ onOpenChange }: { onOpenChange: (open: boolean) => void }) {
  const social = useSocialActions(me.id, async () => {}, expire);
  return (
    <ProfileDialog
      userId={friend}
      table={table}
      bets={[]}
      user={me}
      expire={expire}
      peopleKey={peopleKey}
      social={social}
      onOpenBet={() => {}}
      onOpenChange={onOpenChange}
    />
  );
}

function Host() {
  const tableActions = useTableActions(me.id, async () => {}, expire);
  const betActions = useBetActions(me.id, async () => {}, expire);
  return (
    <HostPanel
      table={table}
      bets={[]}
      balances={[]}
      tableActions={tableActions}
      betActions={betActions}
      user={me}
      expire={expire}
      peopleKey={peopleKey}
    />
  );
}

beforeEach(() => {
  localStorage.clear();
  feeds.clear();
  vi.clearAllMocks();
  vi.mocked(getProfile).mockImplementation(async (id) => ({ id, username: "friend", hue: 2 }));
  vi.mocked(getRelationships).mockResolvedValue({
    followingIds: [],
    followerIds: [],
    blockedIds: [],
  });
  vi.mocked(listSettlementGroups).mockResolvedValue([]);
  vi.mocked(submitReport).mockResolvedValue();
  vi.mocked(markRead).mockResolvedValue();
});

describe("reports", () => {
  it("reports a message from the chat's ⋯ menu with the message as context", async () => {
    vi.mocked(getChatUnread).mockResolvedValue({ firstUnreadMessageId: null, unreadCount: 0 });
    vi.mocked(getChatWindow).mockResolvedValue({
      items: [
        {
          id: "123e4567-e89b-42d3-a456-426614174020",
          tableId: table.id,
          kind: "text",
          authorId: friend,
          text: "you stink",
          createdAt: "2026-11-13T12:00:00Z",
        },
      ],
      previousCursor: null,
      nextCursor: null,
    });
    wrap(<Chat />);
    const menu = await screen.findByRole("button", { name: "More options for friend" });
    fireEvent.keyDown(menu, { key: "Enter" });
    fireEvent.click(await screen.findByRole("menuitem", { name: /Report/ }));
    const dialog = within(await screen.findByRole("dialog"));
    expect(dialog.getByText("“you stink”")).toBeInTheDocument();
    fireEvent.click(dialog.getByRole("tab", { name: "Spam" }));
    fireEvent.click(dialog.getByRole("button", { name: /Send report/ }));
    await waitFor(() =>
      expect(submitReport).toHaveBeenCalledWith(
        {
          targetId: friend,
          reason: "spam",
          note: "",
          tableId: table.id,
          messageId: "123e4567-e89b-42d3-a456-426614174020",
        },
        expect.any(String),
      ),
    );
    expect(await screen.findByText("Report sent")).toBeInTheDocument();
  });

  it("needs details for an 'Other' report", async () => {
    wrap(<Profile onOpenChange={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: /Report/ }));
    const dialog = within(screen.getAllByRole("dialog").at(-1)!);
    fireEvent.click(dialog.getByRole("tab", { name: "Other" }));
    expect(dialog.getByRole("button", { name: /Send report/ })).toBeDisabled();
    fireEvent.change(dialog.getByLabelText("Details"), { target: { value: "Spamming links" } });
    expect(dialog.getByRole("button", { name: /Send report/ })).toBeEnabled();
  });
});

describe("tomatoes", () => {
  it("throws a tomato from the profile and closes it", async () => {
    vi.mocked(throwTomato).mockResolvedValue();
    const onOpenChange = vi.fn();
    wrap(<Profile onOpenChange={onOpenChange} />);
    fireEvent.click(await screen.findByRole("button", { name: "Throw a tomato at friend" }));
    await waitFor(() =>
      expect(throwTomato).toHaveBeenCalledWith(table.id, friend, expect.any(String)),
    );
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });

  it("can't throw when a host turned off your reactions", async () => {
    const Muted = () => {
      const social = useSocialActions(me.id, async () => {}, expire);
      return (
        <ProfileDialog
          userId={friend}
          table={{ ...table, disabledPermissions: { [me.id]: ["react"] } }}
          bets={[]}
          user={me}
          expire={expire}
          peopleKey={peopleKey}
          social={social}
          onOpenBet={() => {}}
          onOpenChange={() => {}}
        />
      );
    };
    wrap(<Muted />);
    expect(await screen.findByRole("button", { name: "Throw a tomato at friend" })).toBeDisabled();
  });

  it("plays only throws that arrive while you're watching", async () => {
    const throwAt = (id: string): TomatoThrow => ({
      id,
      throwerId: friend,
      targetId: me.id,
      thrownAt: "2026-11-13T12:00:00Z",
    });
    vi.mocked(listTomatoes).mockResolvedValueOnce([
      throwAt("123e4567-e89b-42d3-a456-426614174030"),
    ]);
    wrap(<TomatoLayer tableId={table.id} user={me} expire={expire} peopleKey={peopleKey} />);
    await waitFor(() => expect(listTomatoes).toHaveBeenCalledTimes(1));
    // Let the first response land: throws already there when you open the table aren't replayed.
    await act(async () => {
      await vi.mocked(listTomatoes).mock.results[0]!.value;
    });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    vi.mocked(listTomatoes).mockResolvedValue([
      throwAt("123e4567-e89b-42d3-a456-426614174030"),
      throwAt("123e4567-e89b-42d3-a456-426614174031"),
    ]);
    act(() =>
      feeds.get(`/user/queue/tables/${table.id}`)!({
        eventId: "123e4567-e89b-42d3-a456-426614174099",
        type: "tomatoes.changed",
        schemaVersion: 1,
        occurredAt: "2026-11-13T12:00:01Z",
        tableId: table.id,
      }),
    );
    expect(await screen.findAllByRole("status")).toHaveLength(1);
    expect(screen.getByRole("status")).toHaveTextContent(/threw a tomato at You|Tomato splat/);
  });
});

describe("quick picks", () => {
  it("shows the share code and lets a host turn Quick Picks off or rotate the code", async () => {
    vi.mocked(setQuickPicksEnabled).mockResolvedValue();
    vi.mocked(rotateQuickPickCode).mockResolvedValue("ABCDEF");
    wrap(<Host />);
    expect(screen.getByText("K4R7QX")).toBeInTheDocument();
    expect(screen.getByText(/Coming soon/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /New code/ }));
    fireEvent.click(await screen.findByRole("button", { name: "New code" }));
    await waitFor(() =>
      expect(rotateQuickPickCode).toHaveBeenCalledWith(table.id, expect.any(String)),
    );
    expect(await screen.findByText(/New code: ABCDEF/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/^Turn on Quick Picks/));
    await waitFor(() =>
      expect(setQuickPicksEnabled).toHaveBeenCalledWith(table.id, false, expect.any(String)),
    );
  });
});

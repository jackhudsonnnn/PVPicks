import { renderWithQuery } from "@/test/render";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile } from "@/features/people/api";
import {
  type Table,
  getMemberBalance,
  listTomatoes,
  rotateQuickPickCode,
  searchTableMembers,
  setQuickPicksEnabled,
  throwTomato,
  type TomatoThrow,
} from "./api";
import { TableChat } from "@/features/chat/chat";
import { HostPanel } from "@/features/tables/components/host-panel";
import { PeersPanel } from "@/features/tables/components/peers";
import { BalanceDialog } from "@/features/tables/components/balance-dialog";
import { ProfileDialog } from "@/features/social/profile";
import { TomatoLayer } from "@/features/tables/components/tomatoes";
import { useBetActions } from "@/features/bets/actions";
import { getChatUnread, getChatWindow, markRead } from "@/features/chat/api";
import type { Invalidation } from "@/lib/realtime-connection";
import { listSettlementGroups } from "@/features/settlements/api";
import { useSocialActions } from "@/features/social/actions";
import { getRelationships, submitReport } from "@/features/social/api";
import { useTableActions } from "./actions";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("@/features/chat/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/chat/api")>()),
  getChatUnread: vi.fn(),
  getChatWindow: vi.fn(),
  markRead: vi.fn(),
}));
vi.mock("@/features/social/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/social/api")>()),
  getRelationships: vi.fn(),
  submitReport: vi.fn(),
}));
vi.mock("@/features/settlements/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/settlements/api")>()),
  listSettlementGroups: vi.fn(),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  searchTableMembers: vi.fn(),
  listTomatoes: vi.fn(),
  throwTomato: vi.fn(),
  setQuickPicksEnabled: vi.fn(),
  rotateQuickPickCode: vi.fn(),
  getMemberBalance: vi.fn(),
}));
const feeds = vi.hoisted(() => new Map<string, (event: Invalidation) => void>());
vi.mock("@/features/bets/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/bets/api")>()),
  getMemberRecord: vi.fn(async () => ({ played: 0, won: 0, lost: 0, netCents: 0 })),
  listMemberBets: vi.fn(async () => ({ items: [], nextCursor: null })),
}));
vi.mock("@/lib/realtime", () => ({
  useRealtimeStatus: () => "live",
  useRealtimeFeed: (destination: string | null, listener: (event: Invalidation) => void) => {
    if (destination) feeds.set(destination, listener);
  },
}));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const table: Table = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  name: "Game Night",
  emoji: "🎲",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  hostsOnlyBets: false,
  u2pickEnabled: true,
  pollsEnabled: true,
  jackblackAccess: "everyone" as const,
  holdemAccess: "everyone" as const,
  quickPicksEnabled: true,
  quickPickSharing: true,
  quickPickCode: "K4R7QX",
  disabledPermissions: {},
};
const expire = vi.fn();
const peopleKey = ["backend-tables", me.id, "people"];

function wrap(children: ReactNode) {
  return renderWithQuery(children);
}

function Chat() {
  const social = useSocialActions(me.id, async () => {}, expire);
  return <TableChat user={me} table={table} expire={expire} social={social} />;
}

function Profile({
  onOpenChange,
  onThrown = () => {},
  canThrow = true,
}: {
  onOpenChange: (open: boolean) => void;
  onThrown?: () => void;
  canThrow?: boolean;
}) {
  const social = useSocialActions(me.id, async () => {}, expire);
  return (
    <ProfileDialog
      userId={friend}
      table={table}
      user={me}
      expire={expire}
      peopleKey={peopleKey}
      social={social}
      canThrow={canThrow}
      onThrown={onThrown}
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
    friendIds: [],
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
  it("throws a tomato from the profile, then clears the way to the felt", async () => {
    vi.mocked(throwTomato).mockResolvedValue();
    const onThrown = vi.fn();
    wrap(<Profile onOpenChange={() => {}} onThrown={onThrown} />);
    fireEvent.click(await screen.findByRole("button", { name: "Throw a tomato at friend" }));
    await waitFor(() =>
      expect(throwTomato).toHaveBeenCalledWith(table.id, friend, expect.any(String)),
    );
    await waitFor(() => expect(onThrown).toHaveBeenCalled());
  });

  it("hides Throw when they aren't around the felt you can see", async () => {
    wrap(<Profile onOpenChange={() => {}} canThrow={false} />);
    expect(await screen.findByRole("button", { name: "Report" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Throw a tomato/ })).not.toBeInTheDocument();
  });

  it("can't throw when a host turned off your reactions", async () => {
    const Muted = () => {
      const social = useSocialActions(me.id, async () => {}, expire);
      return (
        <ProfileDialog
          userId={friend}
          table={{ ...table, disabledPermissions: { [me.id]: ["react"] } }}
          user={me}
          expire={expire}
          peopleKey={peopleKey}
          social={social}
          canThrow
          onThrown={() => {}}
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

  it("searches a big table's members on the server and shows only the matches", async () => {
    const crowd = Array.from(
      { length: 30 },
      (_, i) => `123e4567-e89b-42d3-a456-4266141742${String(i).padStart(2, "0")}`,
    );
    const names = new Map(crowd.map((id, i) => [id, `crowd${i}`]));
    names.set(friend, "friend");
    vi.mocked(getProfile).mockImplementation(async (id) => ({
      id,
      username: names.get(id) ?? "me",
      hue: 1,
    }));
    vi.mocked(searchTableMembers).mockResolvedValue({ items: [crowd[7]!], nextCursor: null });
    wrap(
      <PeersPanel
        table={{ ...table, memberIds: [me.id, friend, ...crowd] }}
        balances={{ data: [], error: null }}
        onSelectUser={() => {}}
        onSelectBalance={() => {}}
        user={me}
        expire={expire}
        peopleKey={peopleKey}
      />,
    );
    expect(await screen.findByText("crowd0")).toBeInTheDocument();
    expect(screen.queryByText("crowd25")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search members"), { target: { value: "Crowd7" } });
    expect(await screen.findByText("crowd7")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("crowd0")).not.toBeInTheDocument());
    expect(searchTableMembers).toHaveBeenCalledWith(table.id, "crowd7", null, expect.anything());
    fireEvent.change(screen.getByLabelText("Search members"), { target: { value: "no spaces" } });
    expect(await screen.findByText(/up to 15 letters/)).toBeInTheDocument();
  });

  it("opens a peer's profile from their name and their balance's what-if from the balance", async () => {
    vi.mocked(getProfile).mockImplementation(async (id) => ({
      id,
      username: id === friend ? "friend" : "me",
      hue: 1,
    }));
    const onSelectUser = vi.fn();
    const onSelectBalance = vi.fn();
    wrap(
      <PeersPanel
        table={table}
        balances={{
          data: [
            { userId: me.id, balanceCents: 0n, unsettledCents: 0n },
            { userId: friend, balanceCents: -250n, unsettledCents: 50n },
          ],
          error: null,
        }}
        onSelectUser={onSelectUser}
        onSelectBalance={onSelectBalance}
        user={me}
        expire={expire}
        peopleKey={peopleKey}
      />,
    );
    fireEvent.click(await screen.findByRole("button", { name: "friend's profile" }));
    expect(onSelectUser).toHaveBeenCalledWith(friend);
    fireEvent.click(screen.getByRole("button", { name: "friend's balance, -2.50 points" }));
    expect(onSelectBalance).toHaveBeenCalledWith(friend);
    expect(onSelectUser).toHaveBeenCalledTimes(1);
    // One balance: no Settle/True split.
    expect(screen.queryByText("True")).not.toBeInTheDocument();
  });

  it("adds each checked ticket's win to the balance, with all win and all lose", async () => {
    vi.mocked(getProfile).mockResolvedValue({ id: friend, username: "friend", hue: 1 });
    vi.mocked(getMemberBalance).mockResolvedValue({
      userId: friend,
      balanceCents: -950n,
      unsettledCents: 150n,
      tickets: [
        {
          betId: "123e4567-e89b-42d3-a456-426614174020",
          mode: "u2pick",
          question: "Rain today?",
          choice: "Yes",
          loseCents: -300n,
          winCents: 450n,
        },
        {
          betId: "123e4567-e89b-42d3-a456-426614174021",
          mode: "jackblack",
          question: "Jackblack",
          loseCents: -800n,
          winCents: 800n,
        },
      ],
    });
    wrap(
      <BalanceDialog
        tableId={table.id}
        memberId={friend}
        betsKey={["backend-tables", me.id, table.id, "bets"]}
        onOpenChange={() => {}}
        user={me}
        expire={expire}
        peopleKey={peopleKey}
      />,
    );
    const dialog = within(await screen.findByRole("dialog"));
    expect(await dialog.findByText("Rain today?")).toBeInTheDocument();
    expect(getMemberBalance).toHaveBeenCalledWith(table.id, friend, expect.anything());
    expect(dialog.getByText("Win?")).toBeInTheDocument();
    // Nothing checked: the what-if is the balance, every ticket at its loss.
    expect(dialog.getAllByText("-9.50")).toHaveLength(2);
    expect(dialog.getByText("Worst case")).toBeInTheDocument();

    fireEvent.click(dialog.getByLabelText("Rain today?: wins"));
    expect(dialog.getByText("-9.50")).toBeInTheDocument();
    expect(dialog.getByText("-2.00")).toBeInTheDocument();
    expect(dialog.getByText("+4.50")).toBeInTheDocument();

    fireEvent.click(dialog.getByRole("button", { name: "All win" }));
    expect(dialog.getByText("If all win")).toBeInTheDocument();
    expect(dialog.getByText("+14.00")).toBeInTheDocument();
    expect(dialog.getByText("Best case")).toBeInTheDocument();
    expect(dialog.getByRole("button", { name: "All win" })).toBeDisabled();

    fireEvent.click(dialog.getByRole("button", { name: "All lose" }));
    expect(dialog.getAllByText("-9.50")).toHaveLength(2);
    expect(dialog.getByRole("button", { name: "All lose" })).toBeDisabled();
  });
});

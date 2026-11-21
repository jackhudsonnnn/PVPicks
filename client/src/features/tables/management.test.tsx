import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile } from "@/features/people/api";
import {
  type Table,
  addMembers,
  createTable,
  deleteTable,
  listTables,
  removeMember,
  setHost,
  setPermission,
  setRemainderRule,
  updateTableSettings,
} from "./api";
import { FriendsPage } from "@/features/social/friends";
import { CreateTableDialog } from "@/features/tables/components/create-table-dialog";
import { TableCards } from "@/features/tables/components/table-cards";
import { SidebarTables } from "@/features/tables/components/sidebar-tables";
import { AboutPanel, HostPanel } from "@/features/tables/components/host-panel";
import { useBetActions } from "@/features/bets/actions";
import { addableFollowers, tableNameProblem } from "./rules";
import { peopleIn, searchTerm } from "@/features/social/rules";
import {
  getRelationships,
  listInvites,
  respondToInvite,
  searchUsers,
  setBlock,
  setFollow,
} from "@/features/social/api";
import { describeOutcomes, useTableActions } from "./actions";
import { summaryOf } from "@/test/api-fixtures";

vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("@/features/social/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/social/api")>()),
  getRelationships: vi.fn(),
  listInvites: vi.fn(),
  searchUsers: vi.fn(),
  setFollow: vi.fn(),
  setBlock: vi.fn(),
  respondToInvite: vi.fn(),
}));
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  createTable: vi.fn(),
  updateTableProfile: vi.fn(),
  updateTableSettings: vi.fn(),
  setRemainderRule: vi.fn(),
  deleteTable: vi.fn(),
  addMembers: vi.fn(),
  removeMember: vi.fn(),
  setHost: vi.fn(),
  setPermission: vi.fn(),
  listTables: vi.fn(),
}));
vi.mock("@/features/settlements/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/settlements/api")>()),
  listSettlementGroups: vi.fn(async () => []),
}));
vi.mock("@/features/chat/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/chat/api")>()),
  listLatestMessages: vi.fn(async () => []),
}));
vi.mock("@/lib/realtime", () => ({ useRealtimeFeed: () => {} }));
const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
  useNavigate: () => navigate,
}));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const fan = "123e4567-e89b-42d3-a456-426614174002";
const stranger = "123e4567-e89b-42d3-a456-426614174003";
const names: Record<string, string> = { [friend]: "friend", [fan]: "fan", [stranger]: "stranger" };
const table: Table = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  name: "Game Night",
  emoji: "🎲",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
  pollsEnabled: true,
  blackjackAccess: "everyone" as const,
  quickPicksEnabled: false,
  quickPickSharing: false,
  subscribedTo: [],
  disabledPermissions: { [friend]: ["gifs"] },
};
const expire = vi.fn();

function wrap(children: ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      {children}
    </QueryClientProvider>,
  );
}

function Members({ value }: { value: Table }) {
  const tableActions = useTableActions(me.id, async () => {}, expire);
  const betActions = useBetActions(me.id, async () => {}, expire);
  const props = {
    table: value,
    bets: [],
    balances: [],
    tableActions,
    betActions,
    user: me,
    expire,
    peopleKey: ["backend-tables", me.id, "people"],
  };
  return value.hostIds.includes(me.id) ? <HostPanel {...props} /> : <AboutPanel {...props} />;
}

beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(getProfile).mockImplementation(async (id) => ({
    id,
    username: names[id] ?? "?",
    hue: 1,
  }));
  vi.mocked(getRelationships).mockResolvedValue({
    followingIds: [friend, stranger],
    followerIds: [friend, fan],
    friendIds: [friend],
    blockedIds: [],
  });
  vi.mocked(listInvites).mockResolvedValue([]);
});

describe("rules", () => {
  it("validates table names and usernames like the server", () => {
    expect(tableNameProblem("  ")).toMatch(/name/);
    expect(tableNameProblem("x".repeat(15))).toMatch(/14/);
    expect(tableNameProblem("🙂".repeat(14))).toBeNull();
    expect(tableNameProblem(" Game night ")).toBeNull();
    expect(searchTerm(" Pro ")).toBe("pro");
    expect(searchTerm("no spaces")).toBeNull();
    expect(searchTerm("x".repeat(16))).toBeNull();
  });

  it("derives people lists and who can be added", () => {
    const relationships = {
      followingIds: [friend, stranger],
      followerIds: [friend, fan],
      friendIds: [friend],
      blockedIds: [],
    };
    expect(peopleIn("followers", relationships)).toEqual([friend, fan]);
    expect(peopleIn("following", relationships)).toEqual([friend, stranger]);
    expect(addableFollowers(table, [friend, fan, stranger], [stranger])).toEqual([fan]);
    expect(
      describeOutcomes([
        { userId: friend, outcome: "added" },
        { userId: fan, outcome: "invited" },
        { userId: stranger, outcome: "not_added" },
      ]),
    ).toBe("1 added, 1 invited, 1 couldn't be added");
  });
});

describe("home", () => {
  it("creates a table with followers and reports who joined", async () => {
    vi.mocked(createTable).mockResolvedValue({
      tableId: table.id,
      outcomes: [
        { userId: friend, outcome: "added" },
        { userId: fan, outcome: "invited" },
      ],
    });
    wrap(<CreateTableDialog user={me} expire={expire} />);
    fireEvent.click(screen.getByRole("button", { name: "New table" }));
    fireEvent.click(await screen.findByRole("button", { name: "Create table" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Give the table a name");
    fireEvent.change(screen.getByLabelText("Table name"), { target: { value: "  Game Night " } });
    fireEvent.click(screen.getByRole("tab", { name: "🔥" }));
    fireEvent.click(await screen.findByLabelText("friend"));
    fireEvent.click(await screen.findByLabelText("fan"));
    fireEvent.click(screen.getByRole("button", { name: "Create table" }));
    await waitFor(() =>
      expect(createTable).toHaveBeenCalledWith(
        { name: "Game Night", emoji: "🔥", memberIds: [friend, fan] },
        expect.any(String),
      ),
    );
    expect(await screen.findByText("Table created: 1 added, 1 invited.")).toBeInTheDocument();
  });

  it("rejects names longer than a table card fits", async () => {
    wrap(<CreateTableDialog user={me} expire={expire} />);
    fireEvent.click(screen.getByRole("button", { name: "New table" }));
    fireEvent.change(await screen.findByLabelText("Table name"), {
      target: { value: "Fifteen chars!!" },
    });
    expect(screen.getByText("15/14")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Create table" }));
    expect(screen.getByRole("alert")).toHaveTextContent("14 characters or fewer");
    expect(createTable).not.toHaveBeenCalled();
  });

  it("without the sidebar, shows requests and tables as cards, and accepts a request", async () => {
    vi.mocked(listTables).mockResolvedValue([summaryOf({ ...table, name: "Sunday Squad" }, me.id)]);
    vi.mocked(listInvites).mockResolvedValue([
      {
        id: "123e4567-e89b-42d3-a456-426614174020",
        tableId: table.id,
        tableName: "Game Night",
        tableEmoji: "🎲",
        fromId: friend,
        userId: me.id,
        createdAt: "2026-11-13T12:00:00Z",
        memberPreview: [friend],
        memberCount: 1,
      },
    ]);
    vi.mocked(respondToInvite).mockResolvedValue();
    wrap(<TableCards user={me} expire={expire} />);
    expect(await screen.findByText("Sunday Squad")).toBeInTheDocument();
    expect(await screen.findByText("No messages yet")).toBeInTheDocument();
    expect(await screen.findByText("friend wants you at this table. Accept?")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Request to join Game Night" }));
    fireEvent.click(await screen.findByRole("button", { name: "Accept" }));
    await waitFor(() =>
      expect(respondToInvite).toHaveBeenCalledWith(
        "123e4567-e89b-42d3-a456-426614174020",
        true,
        expect.any(String),
      ),
    );
    expect(await screen.findByText("Joined the table.")).toBeInTheDocument();
  });
});

describe("table list with the desktop sidebar", () => {
  const request = (id: string, tableName: string, createdAt: string) => ({
    id,
    tableId: table.id,
    tableName,
    tableEmoji: "🎲",
    fromId: friend,
    userId: me.id,
    createdAt,
    memberPreview: [friend],
    memberCount: 1,
  });
  const older = request(
    "123e4567-e89b-42d3-a456-426614174021",
    "Older Ask",
    "2026-11-10T12:00:00Z",
  );
  const newer = request(
    "123e4567-e89b-42d3-a456-426614174022",
    "Newer Ask",
    "2026-11-15T12:00:00Z",
  );
  const quiet = summaryOf(
    { ...table, id: "123e4567-e89b-42d3-a456-426614174031", name: "Quiet" },
    me.id,
  );
  const busy = summaryOf(
    { ...table, id: "123e4567-e89b-42d3-a456-426614174032", name: "Busy" },
    me.id,
    { lastBetAt: "2026-11-16T20:00:00Z" },
  );

  beforeEach(() => {
    vi.spyOn(window, "matchMedia").mockImplementation(
      (query: string) =>
        ({
          matches: query === "(min-width: 68.75rem)",
          media: query,
          addEventListener: () => {},
          removeEventListener: () => {},
        }) as unknown as MediaQueryList,
    );
    vi.mocked(listTables).mockResolvedValue([quiet, busy]);
  });
  afterEach(() => vi.restoreAllMocks());

  it("on Home shows only Requests, newest first, and answers them from the sidebar", async () => {
    vi.mocked(listInvites).mockResolvedValue([older, newer]);
    vi.mocked(respondToInvite).mockResolvedValue();
    wrap(<SidebarTables user={me} pathname="/" expire={expire} />);
    expect(await screen.findByText("Requests")).toBeInTheDocument();
    const rows = await screen.findAllByRole("button", { name: /Request to join/ });
    expect(rows.map((row) => row.getAttribute("aria-label"))).toEqual([
      "Request to join Newer Ask",
      "Request to join Older Ask",
    ]);
    expect(screen.queryByText("Busy")).not.toBeInTheDocument();
    fireEvent.click(rows[0]!);
    fireEvent.click(await screen.findByRole("button", { name: "Accept" }));
    await waitFor(() =>
      expect(respondToInvite).toHaveBeenCalledWith(newer.id, true, expect.any(String)),
    );
    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: "/tables/$tableId",
        params: { tableId: newer.tableId },
      }),
    );
  });

  it("on Home shows nothing without requests", async () => {
    const { container } = wrap(<SidebarTables user={me} pathname="/" expire={expire} />);
    await waitFor(() => expect(listInvites).toHaveBeenCalled());
    await waitFor(() => expect(listTables).toHaveBeenCalled());
    expect(screen.queryByText("Requests")).not.toBeInTheDocument();
    expect(screen.queryByText("Busy")).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });

  it("elsewhere switches between My tables (by most recent bet) and Requests", async () => {
    vi.mocked(listInvites).mockResolvedValue([older, newer]);
    wrap(<SidebarTables user={me} pathname="/friends" expire={expire} />);
    expect(
      await screen.findByRole("tab", { name: "My tables", selected: true }),
    ).toBeInTheDocument();
    const busyRow = await screen.findByText("Busy");
    expect(busyRow.compareDocumentPosition(screen.getByText("Quiet"))).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    fireEvent.click(screen.getByRole("tab", { name: "Requests" }));
    expect(
      await screen.findByRole("button", { name: "Request to join Newer Ask" }),
    ).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("Busy")).not.toBeInTheDocument());
  });

  it("elsewhere lists just My tables when there are no requests", async () => {
    wrap(<SidebarTables user={me} pathname="/friends" expire={expire} />);
    expect(await screen.findByText("Busy")).toBeInTheDocument();
    expect(screen.getByText("My tables")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("pages a long table list 20 at a time", async () => {
    const many = Array.from({ length: 25 }, (_, i) =>
      summaryOf(
        {
          ...table,
          id: `123e4567-e89b-42d3-a456-4266141741${String(i).padStart(2, "0")}`,
          name: `Table ${i}`,
        },
        me.id,
        { joinedAt: `2026-11-${String(25 - i).padStart(2, "0")}T00:00:00Z` },
      ),
    );
    vi.mocked(listTables).mockResolvedValue(many);
    wrap(<SidebarTables user={me} pathname="/friends" expire={expire} />);
    expect(await screen.findByText("Table 0")).toBeInTheDocument();
    expect(screen.getByText("Table 19")).toBeInTheDocument();
    expect(screen.queryByText("Table 20")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "More tables" }));
    expect(await screen.findByText("Table 24")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "More tables" })).not.toBeInTheDocument();
  });

  it("leaves requests off Home's table cards", async () => {
    vi.mocked(listInvites).mockResolvedValue([newer]);
    wrap(<TableCards user={me} expire={expire} />);
    expect(await screen.findByText("Busy")).toBeInTheDocument();
    expect(screen.queryByText("Newer Ask")).not.toBeInTheDocument();
  });
});

describe("Home table cards without the sidebar", () => {
  it("puts requests (newest first) before your tables, which go by most recent bet", async () => {
    const request = (id: string, tableName: string, createdAt: string) => ({
      id,
      tableId: table.id,
      tableName,
      tableEmoji: "🎲",
      fromId: friend,
      userId: me.id,
      createdAt,
      memberPreview: [friend],
      memberCount: 120,
    });
    vi.mocked(listInvites).mockResolvedValue([
      request("123e4567-e89b-42d3-a456-426614174041", "Old Ask", "2026-11-01T00:00:00Z"),
      request("123e4567-e89b-42d3-a456-426614174042", "New Ask", "2026-11-15T00:00:00Z"),
    ]);
    vi.mocked(listTables).mockResolvedValue([
      summaryOf({ ...table, id: "123e4567-e89b-42d3-a456-426614174051", name: "Quiet" }, me.id),
      summaryOf({ ...table, id: "123e4567-e89b-42d3-a456-426614174052", name: "Busy" }, me.id, {
        lastBetAt: "2026-11-16T00:00:00Z",
      }),
    ]);
    wrap(<TableCards user={me} expire={expire} />);
    expect(await screen.findByRole("heading", { name: "Tables" })).toBeInTheDocument();
    const requests = within(await screen.findByRole("group", { name: "Requests" }));
    expect(
      requests
        .getAllByRole("button", { name: /Request to join/ })
        .map((b) => b.getAttribute("aria-label")),
    ).toEqual(["Request to join New Ask", "Request to join Old Ask"]);
    const tables = within(screen.getByRole("group", { name: "Tables" }));
    const busy = await tables.findByText("Busy");
    expect(busy.compareDocumentPosition(tables.getByText("Quiet"))).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
    // The request card counts everyone at the table, not just the preview.
    expect(requests.getAllByText("+119")).toHaveLength(2);
  });
});

describe("friends", () => {
  it("opens on Find, then lists followers to follow back, with no counts or Friends tab", async () => {
    vi.mocked(setFollow).mockResolvedValue();
    vi.mocked(setBlock).mockResolvedValue();
    wrap(<FriendsPage user={me} expire={expire} />);
    const tabs = await screen.findAllByRole("tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual(
      expect.arrayContaining(["Find", "Followers", "Following", "Blocked"]),
    );
    expect(screen.getByRole("tab", { name: "Find" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: /Friends|Requests|·/ })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "My network" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "Followers" }));
    // Cards in a sideways row, like the settle-ups.
    const row = await screen.findByLabelText(/Everyone who follows you/);
    expect(row).toHaveAttribute("data-scroll-direction", "horizontal");
    expect(await within(row).findByText("friend")).toBeInTheDocument();
    expect(await within(row).findByText("fan")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Follow" }));
    expect(await screen.findByText(/so you'll be friends/)).toBeInTheDocument();
    expect(setFollow).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(setFollow).toHaveBeenCalledWith(fan, true, expect.any(String)));
    // Followers come newest first: friend, then fan.
    fireEvent.click(screen.getAllByRole("button", { name: "Block" }).at(-1)!);
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(setBlock).toHaveBeenCalledWith(fan, true, expect.any(String)));
  });

  it("asks before unfollowing or unblocking", async () => {
    vi.mocked(getRelationships).mockResolvedValue({
      followingIds: [friend],
      followerIds: [friend],
      friendIds: [friend],
      blockedIds: [stranger],
    });
    vi.mocked(setFollow).mockResolvedValue();
    vi.mocked(setBlock).mockResolvedValue();
    wrap(<FriendsPage user={me} expire={expire} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Following" }));
    fireEvent.click(await screen.findByRole("button", { name: "Unfollow" }));
    expect(await screen.findByText(/no longer be friends/)).toBeInTheDocument();
    expect(setFollow).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(setFollow).toHaveBeenCalledWith(friend, false, expect.any(String)));
    fireEvent.click(screen.getByRole("tab", { name: "Blocked" }));
    fireEvent.click(await screen.findByRole("button", { name: "Unblock" }));
    expect(await screen.findByText(/aren't restored/)).toBeInTheDocument();
    expect(setBlock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(setBlock).toHaveBeenCalledWith(stranger, false, expect.any(String)));
  });

  it("searches valid usernames only", async () => {
    vi.mocked(searchUsers).mockResolvedValue({
      items: [{ id: stranger, username: "stranger", hue: 2 }],
      nextCursor: null,
    });
    wrap(<FriendsPage user={me} expire={expire} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Find" }));
    fireEvent.change(screen.getByLabelText("Search usernames"), { target: { value: "bad name!" } });
    expect(screen.getByText(/up to 15 letters/)).toBeInTheDocument();
    expect(searchUsers).not.toHaveBeenCalled();
    const box = screen.getByLabelText("Search usernames");
    fireEvent.change(box, { target: { value: "st" } });
    expect(screen.getByText(/at least 3 characters/)).toBeInTheDocument();
    // Typing quickly searches once, after the pause.
    fireEvent.change(box, { target: { value: "Str" } });
    fireEvent.change(box, { target: { value: "Stra" } });
    fireEvent.change(box, { target: { value: "Stran" } });
    expect(searchUsers).not.toHaveBeenCalled();
    expect(await screen.findByText("stranger", {}, { timeout: 2000 })).toBeInTheDocument();
    expect(searchUsers).toHaveBeenCalledOnce();
    expect(searchUsers).toHaveBeenCalledWith("stran", null, expect.any(AbortSignal));
  });
});

describe("table members", () => {
  it("lets a host manage roles, permissions and membership", async () => {
    vi.mocked(setHost).mockResolvedValue();
    vi.mocked(setPermission).mockResolvedValue();
    vi.mocked(removeMember).mockResolvedValue();
    wrap(<Members value={table} />);
    expect(await screen.findByText(/1 limit/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /^friend/ }));
    const dialog = within(await screen.findByRole("dialog"));
    fireEvent.click(dialog.getByLabelText(/^GIFs/));
    await waitFor(() =>
      expect(setPermission).toHaveBeenCalledWith(
        table.id,
        friend,
        "gifs",
        true,
        expect.any(String),
      ),
    );
    fireEvent.click(dialog.getByLabelText(/^Host/));
    await waitFor(() =>
      expect(setHost).toHaveBeenCalledWith(table.id, friend, true, expect.any(String)),
    );
    fireEvent.click(dialog.getByRole("button", { name: /Remove from table/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Remove" }));
    await waitFor(() =>
      expect(removeMember).toHaveBeenCalledWith(table.id, friend, expect.any(String)),
    );
  });

  it("adds followers who are not members yet", async () => {
    vi.mocked(addMembers).mockResolvedValue([{ userId: fan, outcome: "invited" }]);
    wrap(<Members value={table} />);
    expect(await screen.findByText("fan")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    await waitFor(() =>
      expect(addMembers).toHaveBeenCalledWith(table.id, [fan], expect.any(String)),
    );
    expect(await screen.findByText("1 invited.")).toBeInTheDocument();
  });

  it("changes settings and deletes the table, explaining blocked deletions", async () => {
    vi.mocked(updateTableSettings).mockResolvedValue();
    vi.mocked(setRemainderRule).mockResolvedValue();
    const { BackendError } = await import("@/lib/api-client");
    vi.mocked(deleteTable).mockRejectedValue(new BackendError("x", 409, "table_archive_unsettled"));
    wrap(<Members value={table} />);
    fireEvent.click(screen.getByLabelText(/^Only hosts start bets/));
    await waitFor(() =>
      expect(updateTableSettings).toHaveBeenCalledWith(
        table.id,
        { hostsOnlyBets: true },
        expect.any(String),
      ),
    );
    fireEvent.keyDown(screen.getByRole("combobox", { name: "Leftover cents go to" }), {
      key: "Enter",
    });
    fireEvent.click(await screen.findByRole("option", { name: "Random" }));
    await waitFor(() =>
      expect(setRemainderRule).toHaveBeenCalledWith(table.id, "random", expect.any(String)),
    );
    fireEvent.click(screen.getByRole("button", { name: /Delete table/ }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
    expect(await screen.findByText(/can't be deleted while bets are active/)).toBeInTheDocument();
  });

  it("shows members read-only settings without host controls", async () => {
    wrap(<Members value={{ ...table, hostIds: [friend] }} />);
    expect(await screen.findByText("Leftover cents go to")).toBeInTheDocument();
    expect(screen.getByText("Your permissions")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.queryByText("Add players")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Delete table/ })).not.toBeInTheDocument();
  });
});

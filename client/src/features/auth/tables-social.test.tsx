import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProfile, type BackendTable } from "./backend-api";
import { BackendFriends } from "./backend-friends";
import { CreateTable, Invitations } from "./backend-home";
import { AboutPanel, HostPanel } from "./backend-host-panel";
import { useBetActions } from "./bet-actions";
import { addableFollowers, peopleIn, searchTerm, tableNameProblem } from "./management-rules";
import {
  getRelationships,
  listInvites,
  respondToInvite,
  searchUsers,
  setBlock,
  setFollow,
} from "./social-api";
import { describeOutcomes, useTableActions } from "./table-actions";
import {
  addMembers,
  createTable,
  deleteTable,
  removeMember,
  setHost,
  setPermission,
  setRemainderRule,
  updateTableSettings,
} from "./tables-api";

vi.mock("./backend-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./backend-api")>()),
  getProfile: vi.fn(),
}));
vi.mock("./social-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./social-api")>()),
  getRelationships: vi.fn(),
  listInvites: vi.fn(),
  searchUsers: vi.fn(),
  setFollow: vi.fn(),
  setBlock: vi.fn(),
  respondToInvite: vi.fn(),
}));
vi.mock("./tables-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./tables-api")>()),
  createTable: vi.fn(),
  updateTableProfile: vi.fn(),
  updateTableSettings: vi.fn(),
  setRemainderRule: vi.fn(),
  deleteTable: vi.fn(),
  addMembers: vi.fn(),
  removeMember: vi.fn(),
  setHost: vi.fn(),
  setPermission: vi.fn(),
}));
vi.mock("./settlement-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./settlement-api")>()),
  listPendingReviews: vi.fn(async () => []),
  listSettlementGroups: vi.fn(async () => []),
  listSettlementCounterparties: vi.fn(async () => []),
}));
vi.mock("./realtime", () => ({ useRealtimeFeed: () => {} }));

const me = { id: "123e4567-e89b-42d3-a456-426614174000", username: "me", hue: 1 };
const friend = "123e4567-e89b-42d3-a456-426614174001";
const fan = "123e4567-e89b-42d3-a456-426614174002";
const stranger = "123e4567-e89b-42d3-a456-426614174003";
const names: Record<string, string> = { [friend]: "friend", [fan]: "fan", [stranger]: "stranger" };
const table: BackendTable = {
  id: "123e4567-e89b-42d3-a456-426614174010",
  name: "Game Night",
  emoji: "🎲",
  memberIds: [me.id, friend],
  hostIds: [me.id],
  remainderRule: "host",
  hostsOnlyBets: false,
  u2pickEnabled: true,
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

function Members({ value }: { value: BackendTable }) {
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
  return value.hostIds.includes(me.id) ? (
    <HostPanel {...props} />
  ) : (
    <AboutPanel {...props} onOpenPeers={() => {}} />
  );
}

async function confirm(label: string) {
  fireEvent.click(await screen.findByRole("button", { name: label }));
  fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
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
    blockedIds: [],
  });
  vi.mocked(listInvites).mockResolvedValue([]);
});

describe("rules", () => {
  it("validates table names and usernames like the server", () => {
    expect(tableNameProblem("  ")).toMatch(/name/);
    expect(tableNameProblem("x".repeat(41))).toMatch(/40/);
    expect(tableNameProblem(" Game night ")).toBeNull();
    expect(searchTerm(" Pro ")).toBe("pro");
    expect(searchTerm("no spaces")).toBeNull();
    expect(searchTerm("x".repeat(16))).toBeNull();
  });

  it("derives people lists and who can be added", () => {
    const relationships = {
      followingIds: [friend, stranger],
      followerIds: [friend, fan],
      blockedIds: [],
    };
    expect(peopleIn("friends", relationships)).toEqual([friend]);
    expect(peopleIn("followers", relationships)).toEqual([friend, fan]);
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
    wrap(<CreateTable user={me} expire={expire} />);
    fireEvent.click(screen.getByText("Create a table"));
    fireEvent.click(screen.getByRole("button", { name: "Create table" }));
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

  it("joins or declines invitations", async () => {
    vi.mocked(listInvites).mockResolvedValue([
      {
        id: "123e4567-e89b-42d3-a456-426614174020",
        tableId: table.id,
        tableName: "Game Night",
        tableEmoji: "🎲",
        fromId: friend,
        userId: me.id,
        createdAt: "2026-11-13T12:00:00Z",
      },
    ]);
    vi.mocked(respondToInvite).mockResolvedValue();
    wrap(<Invitations user={me} expire={expire} />);
    expect(await screen.findByText("Game Night")).toBeInTheDocument();
    expect(await screen.findByText(/From friend/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Join" }));
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

describe("friends", () => {
  it("lists friends and new followers, follows back and blocks", async () => {
    vi.mocked(setFollow).mockResolvedValue();
    vi.mocked(setBlock).mockResolvedValue();
    wrap(<BackendFriends user={me} expire={expire} />);
    const newFollowers = (await screen.findByText("New followers")).parentElement!;
    expect(await within(newFollowers).findByText("fan")).toBeInTheDocument();
    fireEvent.click(within(newFollowers).getByRole("button", { name: "Follow back" }));
    await waitFor(() => expect(setFollow).toHaveBeenCalledWith(fan, true, expect.any(String)));
    expect(screen.getByRole("tab", { name: "Friends · 1" })).toBeInTheDocument();
    fireEvent.click(within(newFollowers).getByRole("button", { name: "Block" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(setBlock).toHaveBeenCalledWith(fan, true, expect.any(String)));
  });

  it("searches valid usernames only", async () => {
    vi.mocked(searchUsers).mockResolvedValue({
      items: [{ id: stranger, username: "stranger", hue: 2 }],
      nextCursor: null,
    });
    wrap(<BackendFriends user={me} expire={expire} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Find" }));
    fireEvent.change(screen.getByLabelText("Search usernames"), { target: { value: "bad name!" } });
    expect(screen.getByText(/up to 15 letters/)).toBeInTheDocument();
    expect(searchUsers).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("Search usernames"), { target: { value: "Stran" } });
    expect(await screen.findByText("stranger")).toBeInTheDocument();
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
    const { BackendError } = await import("./backend-api");
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

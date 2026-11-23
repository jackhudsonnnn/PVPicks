import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { bet as baseBet, summaryOf, table, user } from "@/test/api-fixtures";
import { getProfile } from "@/features/people/api";
import type { Bet } from "./api";
import { getHoldemGame, holdemWorstCase, positionOf, type HoldemGame } from "./holdem";
import { HoldemTicket } from "./components/holdem-ticket";

vi.mock("./holdem", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./holdem")>()),
  getHoldemGame: vi.fn(),
}));
vi.mock("@/features/people/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/people/api")>()),
  getProfile: vi.fn(),
}));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: ReactNode }) => <a>{children}</a>,
}));
vi.mock("@/lib/realtime", () => ({ useRealtimeFeed: () => {}, useRealtimeStatus: () => "live" }));

const other = "123e4567-e89b-42d3-a456-426614174009";
const holdem: Bet = {
  ...(baseBet as Bet),
  modeId: "holdem",
  question: "Hold'em",
  status: "PENDING",
  wagerCents: 100,
  myPick: null,
  myParticipation: "no_guess",
};

const play: HoldemGame = {
  phase: "play",
  hand: 1,
  hands: 2,
  order: [user.id, other],
  bigBlindCents: 100,
  smallBlindCents: 50,
  buyInCents: 10_000,
  stacks: { [user.id]: 9_950, [other]: 9_900 },
  buyIns: { [user.id]: 1, [other]: 1 },
  out: [],
  buttonId: user.id,
  smallBlindId: user.id,
  bigBlindId: other,
  street: "preflop",
  board: [],
  pot: 150,
  currentBet: 100,
  toActId: user.id,
  seats: [
    { userId: other, committed: 100, contributed: 100, folded: false, allIn: false },
    { userId: user.id, committed: 50, contributed: 50, folded: false, allIn: false },
  ],
  me: { hole: ["AS", "KD"], toCall: 50, canRaise: true, minRaiseTo: 200, maxRaiseTo: 10_000 },
  history: [],
  shuffles: [{ round: 1, commitment: "a".repeat(64) }],
};

let client: QueryClient;

function wrap(children: ReactNode) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{children}</QueryClientProvider>);
}

function ticket(start = vi.fn()) {
  const actions = { start, busy: false, pending: null, storageError: null } as never;
  wrap(
    <HoldemTicket
      bet={holdem}
      table={summaryOf(table, user.id)}
      user={user}
      expire={() => {}}
      peopleKey={["people"]}
      actions={actions}
    />,
  );
  return start;
}

beforeEach(() => {
  vi.mocked(getProfile).mockImplementation(async (id) => ({ id, username: "rival", hue: 1 }));
});

describe("hold'em", () => {
  it("knows positions and holds both buy-ins against the limit", () => {
    expect(positionOf(play, user.id)).toBe("BTN · SB");
    expect(positionOf(play, other)).toBe("BB");
    expect(holdemWorstCase(100)).toBe(20_000);
  });

  it("shows your cards, the empty board and pot, and your moves on your turn", async () => {
    vi.mocked(getHoldemGame).mockResolvedValue(play);
    const start = ticket();
    // The header says where the game is; the board is face down before the flop.
    expect(await screen.findByText("Hand 1 / 2")).toBeInTheDocument();
    expect(screen.getByLabelText("Board: no cards yet")).toBeInTheDocument();
    expect(screen.getByText("Pre-flop · your move", { exact: false })).toBeInTheDocument();
    // The rules and the deck fingerprint are in Details, not on the card.
    expect(screen.queryByText(/Deck #/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("A♠")).toBeInTheDocument();
    expect(screen.getByText("1.50")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Call 0.50" }));
    expect(start).toHaveBeenLastCalledWith({
      kind: "holdem-act",
      tableId: table.id,
      betId: holdem.id,
      action: "call",
    });
    // Raising takes over the felt below the board (the seats and moves give way), and X cancels it.
    fireEvent.click(screen.getByRole("button", { name: "Raise…" }));
    expect(screen.queryByRole("list", { name: "Seats" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel raise" }));
    expect(screen.getByRole("list", { name: "Seats" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Raise…" }));
    fireEvent.click(screen.getByRole("button", { name: "Raise 2.00" }));
    expect(start).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: "raise", amount: 200 }),
    );
  });

  it("names your hand once the flop is out and offers quick raise sizes", async () => {
    vi.mocked(getHoldemGame).mockResolvedValue({
      ...play,
      street: "flop",
      board: ["AD", "7C", "2H"],
      me: { ...play.me!, handName: "Pair of aces" },
    });
    const start = ticket();
    expect(await screen.findByText("Pair of aces")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Raise…" }));
    // Pot after the call is 2.00, so a pot raise is to 1.00 + 2.00.
    fireEvent.click(screen.getByRole("button", { name: "Pot" }));
    fireEvent.click(screen.getByRole("button", { name: "Raise 3.00" }));
    expect(start).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: "raise", amount: 300 }),
    );
  });

  it("arms a pre-move on the server and shows it armed until it's played", async () => {
    const waiting = { ...play, toActId: other, me: { hole: ["AS", "KD"] } };
    vi.mocked(getHoldemGame).mockResolvedValue(waiting);
    const start = ticket();
    fireEvent.click(await screen.findByRole("button", { name: "Call any" }));
    expect(start).toHaveBeenLastCalledWith(expect.objectContaining({ action: "call_any" }));
    vi.mocked(getHoldemGame).mockResolvedValue({
      ...waiting,
      me: { ...waiting.me, preMove: "call_any" },
    });
    await client.invalidateQueries();
    const armed = await screen.findByRole("button", { name: "Call any", pressed: true });
    // Tapping it again clears it.
    fireEvent.click(armed);
    expect(start).toHaveBeenLastCalledWith(expect.objectContaining({ action: "no_pre_move" }));
  });

  it("between hands shows everyone's result and hand, and offers the rebuy to whoever busted", async () => {
    vi.mocked(getHoldemGame).mockResolvedValue({
      ...play,
      phase: "results",
      toActId: undefined,
      me: { hole: ["AS", "KD"] },
      rebuyOffered: [user.id],
      stacks: { [user.id]: 0, [other]: 20_000 },
      history: [
        {
          number: 1,
          buttonId: user.id,
          smallBlindId: user.id,
          bigBlindId: other,
          board: ["2C", "7D", "9H", "JS", "3D"],
          showdown: true,
          pots: [{ amount: 20_000, winners: [other], handName: "Pair of nines" }],
          shown: { [other]: ["9S", "9C"] },
          handNames: { [other]: "Three nines" },
          atShowdown: [user.id, other],
          deltas: { [user.id]: -10_000, [other]: 10_000 },
        },
      ],
    } as HoldemGame);
    const start = ticket();
    const results = await screen.findByRole("list", { name: "Hand results" });
    expect(within(results).getByText("Three nines")).toBeInTheDocument();
    expect(within(results).getByText("+100.00")).toBeInTheDocument();
    expect(
      screen.getByText("Out of chips. Rebuy for 100.00?", { exact: false }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Rebuy" }));
    expect(start).toHaveBeenLastCalledWith(expect.objectContaining({ action: "rebuy" }));
    fireEvent.click(screen.getByRole("button", { name: "Sit out the rest" }));
    expect(start).toHaveBeenLastCalledWith(expect.objectContaining({ action: "pass" }));
  });
});

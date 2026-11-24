import { secondsUntil, signed } from "@/features/bets/cards";
import { Spade } from "lucide-react";
import { TicketFooter } from "./ticket-footer";
import { TicketByline } from "./ticket-byline";
import type { BetActions } from "./bet-controls";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import { canBetAt, type Table, type TableSummary } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { formatClock, secondsLeft, ticketStatus } from "@/features/bets/ticket-model";
import {
  deciding,
  mySeat,
  playersOf,
  rulesFromConfig,
  undecided,
  type JackblackGame,
  type JackblackSeat,
} from "@/features/bets/jackblack";
import { CHOICE_NAMES, DealerButton, PlayControls } from "./jackblack-parts";
import { RESULT, handLabel, useJackblackGame } from "@/features/bets/jackblack-game";
import {
  Card,
  CardTicketHeader,
  Deck,
  FaceDown,
  Felt,
  FeltNote,
  FeltRow,
  JoinControls,
  type People,
  PlayerName,
  Tile,
  TimerRing,
  RunItBack,
} from "./card-ticket-shell";

/** Cards in the ticket come out of the shoe at the felt's top left. */
const SHOE_FROM_DEALER = { x: -64, y: 0 };
const SHOE_FROM_SEAT = { x: -48, y: -110 };
/** Seconds between cards in the opening deal. */
const DEAL_GAP = 0.14;

/** One seat in a live round: name, whether they've chosen yet (never what), and their hands. */
function Spot({
  seat,
  order,
  seats,
  timeLeft,
  ...people
}: People & {
  seat: JackblackSeat;
  order: number;
  seats: number;
  /** The share of the decision timer left, while this seat is still choosing. */
  timeLeft: number | null;
}) {
  const { user, expire, peopleKey } = people;
  const thinking = seat.deciding === true;
  return (
    <li
      className={cn(
        "flex min-w-[10rem] shrink-0 snap-start flex-col gap-1 rounded-lg border border-white/10 bg-black/20 p-2",
        seat.userId === user.id && thinking && "border-primary/70 bg-primary/10",
      )}
    >
      <div className="flex items-center gap-1.5 text-[11px]">
        {thinking && timeLeft !== null ? (
          <TimerRing fraction={timeLeft}>
            <PersonAvatar
              id={seat.userId}
              size="xs"
              user={user}
              expire={expire}
              queryKey={peopleKey}
            />
          </TimerRing>
        ) : (
          <PersonAvatar
            id={seat.userId}
            size="xs"
            user={user}
            expire={expire}
            queryKey={peopleKey}
          />
        )}
        <span className="min-w-0 flex-1 truncate font-semibold text-white">
          <PlayerName id={seat.userId} {...people} />
        </span>
        {seat.deciding !== undefined && (
          <span
            className={cn(
              "font-mono text-[10px] font-bold uppercase",
              thinking ? "animate-pulse text-white/70" : "text-win",
            )}
          >
            {thinking ? "Deciding" : "Ready"}
          </span>
        )}
      </div>
      <div className="flex flex-1 items-center gap-1.5">
        {(seat.hands ?? []).map((h, i) => (
          <div
            key={i}
            className={cn("shrink-0 space-y-1 rounded-md p-1", h.current && "ring-2 ring-primary")}
          >
            <div className="flex -space-x-2">
              {h.cards.map((code, c) => (
                <Card
                  key={`${c}-${code}`}
                  code={code}
                  small
                  from={SHOE_FROM_SEAT}
                  delay={
                    h.cards.length <= 2 && seat.hands?.length === 1
                      ? (c * (seats + 1) + order) * DEAL_GAP
                      : 0
                  }
                />
              ))}
            </div>
            <p className="font-mono text-[10px] font-bold text-white/80">
              {handLabel(h, (seat.hands?.length ?? 0) > 1)}
              {h.doubled && " · 2×"}
            </p>
          </div>
        ))}
      </div>
    </li>
  );
}

/**
 * The people row for each phase, always the same height and scrolling sideways: who's in while the join window is
 * open; in a round the seats you may see (your own, or every seat when you're dealing: the server only ever sends
 * the cards you may see); between rounds how everyone did; at the end the standings.
 */
function PeopleRow({
  game,
  status,
  washReason,
  timeLeft,
  ...people
}: People & {
  game: JackblackGame;
  status: ReturnType<typeof ticketStatus>;
  washReason: string | undefined;
  timeLeft: number | null;
}) {
  const { user } = people;
  if (status === "washed") return <FeltNote>{washReason ?? "Washed"}</FeltNote>;
  if (game.phase === "waiting") {
    return (
      <FeltRow label="Players">
        {playersOf(game).map((id) => (
          <Tile key={id} id={id} line="In" highlight={id === user.id} {...people} />
        ))}
      </FeltRow>
    );
  }
  if (game.phase === "done") {
    const totals = Object.entries(game.totals ?? {}).sort(([, a], [, b]) => b - a);
    return (
      <FeltRow label="Standings">
        {totals.map(([id, cents]) => (
          <Tile
            key={id}
            id={id}
            line={cents === 0 ? "Even" : signed(cents)}
            tone={cents > 0 ? "win" : cents < 0 ? "loss" : "muted"}
            highlight={id === user.id}
            {...people}
          />
        ))}
      </FeltRow>
    );
  }
  if (game.phase === "results") {
    const round = game.history?.at(-1);
    if (!round) return null;
    const rows = [
      { id: round.dealerId, cents: round.dealerDeltaCents, label: "Dealer" },
      ...round.seats.map((seat) => ({
        id: seat.userId,
        cents: seat.deltaCents ?? 0,
        label: (seat.hands ?? [])
          .map((h) => (h.result ? RESULT[h.result] : String(h.total)))
          .join("/"),
      })),
    ];
    return (
      <FeltRow label="Round results">
        {rows.map((row) => (
          <Tile
            key={row.id}
            id={row.id}
            line={`${row.label} ${row.cents === 0 ? "±0" : signed(row.cents)}`}
            tone={row.cents > 0 ? "win" : row.cents < 0 ? "loss" : "muted"}
            highlight={row.id === user.id}
            {...people}
          />
        ))}
      </FeltRow>
    );
  }
  const seats = game.seats ?? [];
  const shown = seats
    .map((seat, order) => ({ seat, order }))
    .filter(({ seat }) => seat.hands !== undefined);
  if (shown.length === 0) {
    return <FeltNote>You're watching: only the players see their own cards.</FeltNote>;
  }
  return (
    <FeltRow label={shown.length === 1 ? "Your seat" : "Seats"}>
      {shown.map(({ seat, order }) => (
        <Spot
          key={`${game.round}-${seat.userId}`}
          seat={seat}
          order={order}
          seats={seats.length}
          timeLeft={timeLeft}
          {...people}
        />
      ))}
    </FeltRow>
  );
}

/** The table region: the shoe, then the dealer's hand (or where it will be dealt). */
function TableRegion({
  game,
  revealed,
  dealerName,
}: {
  game: JackblackGame;
  revealed: boolean;
  dealerName: string;
}) {
  const round = game.phase === "results" ? game.history?.at(-1) : undefined;
  const live = game.phase === "play" || game.phase === "dealer";
  const cards = round ? round.dealer : live ? (game.dealer ?? []) : [];
  const seats = game.seats?.length ?? 0;
  const total = round ? round.dealerTotal : game.dealerTotal;
  return (
    <div className="flex w-full items-center gap-3">
      <Deck label="Shoe" />
      <div className="min-w-0 flex-1">
        <div className="flex -space-x-3">
          {cards.length > 0
            ? cards.map((code, c) => (
                <Card
                  key={`${game.round}-${c}-${code}`}
                  code={code}
                  // Face down, cards slide from the shoe in deal order; turned over, the hole card flips.
                  from={round || (revealed && c === 1) ? undefined : SHOE_FROM_DEALER}
                  delay={!revealed && !round ? (c * (seats + 1) + seats) * DEAL_GAP : 0}
                />
              ))
            : [0, 1].map((i) => <FaceDown key={i} />)}
        </div>
        <p className="mt-1 truncate font-mono text-[10px] font-bold uppercase tracking-wider text-white/80">
          {round || live
            ? `Dealer · ${dealerName}${total !== undefined ? ` · ${total > 21 ? "bust" : total}` : ""}`
            : game.phase === "done"
              ? "Game over"
              : "Dealer · drawn at the deal"}
        </p>
      </div>
    </div>
  );
}

/** What's happening, in one line. */
function statusLine(
  game: JackblackGame,
  user: User,
  now: number,
  join: { left: number; joined: boolean; full: boolean; canBet: boolean },
) {
  const left = secondsUntil(game.deadline, now);
  const timer = left !== null ? ` · ${left}s` : "";
  if (game.phase === "waiting") {
    const clock = formatClock(Math.max(0, join.left));
    if (join.joined)
      return join.left > 0 ? `You're in · deal in ${clock}` : "You're in. Dealing now.";
    if (!join.canBet) return "You can't bet at this table.";
    if (join.full) return "The game is full.";
    return `Not in · join within ${clock}`;
  }
  if (game.phase === "done") {
    const mine = game.totals?.[user.id];
    if (mine === undefined) return "Game over.";
    return mine === 0
      ? "You came out even."
      : `You ${mine > 0 ? "won" : "lost"} ${formatCents(Math.abs(mine))}.`;
  }
  if (game.phase === "results")
    return `Round ${game.round} done.${left !== null ? ` Next deal in ${left}s.` : ""}`;
  const dealing = game.dealerId === user.id;
  if (game.phase === "dealer")
    return dealing ? `Your turn to deal${timer}.` : "The dealer's playing their hand…";
  const mine = mySeat(game, user.id);
  if (deciding(game, user.id)) return `Your move${timer}.`;
  if (mine?.choice)
    return `${CHOICE_NAMES[mine.choice]} it is. Waiting on ${undecided(game)}${timer}.`;
  if (dealing) return `You're dealing. ${undecided(game)} still deciding${timer}.`;
  return `${undecided(game)} still deciding${timer}.`;
}

/**
 * A Jackblack bet as a ticket. The felt keeps the same regions in every phase (owner decisions, 2026-11-23): the
 * shoe and the dealer's hand; a row of people that scrolls sideways (who's in, the seats you may see, the round's
 * results, the standings); one status line; and your buttons (join or leave, hit / stand / double / split, the
 * dealer's button). Rules, the shoe audit and every round are in Details. The game refreshes with the table's bet
 * feed, and every few seconds while it's played.
 */
export function JackblackTicket({
  bet,
  table,
  user,
  expire,
  peopleKey,
  className,
  actions,
}: {
  bet: Bet;
  table: Table | TableSummary;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
  className?: string;
  actions?: BetActions;
}) {
  const people = { user, expire, peopleKey };
  const preview = !actions;
  const status = ticketStatus(bet);
  const rules = rulesFromConfig(bet.config);
  const live = !preview && (status === "active" || status === "pending");
  const query = useJackblackGame(bet, user, live, !preview);
  const now = useNow(live);
  const left = secondsLeft(bet, now);
  const blocked = !actions || actions.busy || !!actions.pending || !!actions.storageError;
  const game = query.data;
  const players = game ? playersOf(game) : [];
  const joined = players.includes(user.id);
  const mine = game ? mySeat(game, user.id) : undefined;
  const revealed = !!game?.dealer && game.dealer[1] !== undefined && game.dealer[1] !== "??";
  const dealerId =
    game?.phase === "results" ? game.history?.at(-1)?.dealerId : (game?.dealerId ?? undefined);
  const dealer = usePerson(dealerId ?? bet.proposerId, user, expire, peopleKey);
  const dealerName = dealerId === user.id ? "You" : (dealer?.name ?? "…");
  // Rounds: everyone deals once (counted from who's in until the deal).
  const rounds = game?.rounds ?? Math.max(1, players.length);
  const round =
    !game || game.phase === "waiting" || status === "washed"
      ? 0
      : game.phase === "done"
        ? rounds
        : (game.round ?? 0);
  const start = (intent: Parameters<BetActions["start"]>[0]) => actions?.start(intent);
  const canBet = canBetAt(table, user.id);

  const controls =
    !game || preview ? null : status === "resolved" || status === "washed" ? (
      <RunItBack
        rematch={bet.rematch}
        canBet={canBet}
        disabled={blocked}
        onRun={() => start({ kind: "rematch", tableId: table.id, betId: bet.id })}
      />
    ) : status === "active" && canBet && left > 0 ? (
      <JoinControls
        joined={joined}
        full={players.length >= rules.maxPlayers}
        disabled={blocked}
        onJoin={() => start({ kind: "jackblack-join", tableId: table.id, betId: bet.id })}
        onLeave={() => start({ kind: "jackblack-leave", tableId: table.id, betId: bet.id })}
      />
    ) : mine && game.phase === "play" && mine.deciding !== undefined ? (
      <PlayControls
        key={`${game.round}-${(mine.hands ?? []).map((h) => h.cards.length).join(".")}`}
        seat={mine}
        disabled={blocked}
        onMove={(action) =>
          start({ kind: "jackblack-act", tableId: table.id, betId: bet.id, action })
        }
      />
    ) : game.dealerId === user.id && game.dealerMove ? (
      <DealerButton
        move={game.dealerMove}
        disabled={blocked}
        onTap={() =>
          start({ kind: "jackblack-act", tableId: table.id, betId: bet.id, action: "deal" })
        }
      />
    ) : null;

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <CardTicketHeader
        icon={<Spade className="size-5" />}
        name="Jackblack"
        status={status}
        joinLeft={left}
        progress={`Round ${round} / ${rounds}`}
      />

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <TicketByline bet={bet} table={table} {...people} preview={preview} />
        {preview ? (
          <p className="text-sm text-ticket-muted">
            Everyone who joins deals once, in a random order, and banks that round against the rest.
            Choices are made together: new cards come out once everyone has chosen.
          </p>
        ) : !game ? (
          <Felt
            table={<Deck label="Shoe" />}
            people={
              <FeltNote>
                {query.isPending ? "Shuffling…" : "The game can't be shown right now."}
              </FeltNote>
            }
            status=""
          />
        ) : (
          <Felt
            table={<TableRegion game={game} revealed={revealed} dealerName={dealerName} />}
            people={
              <PeopleRow
                game={game}
                status={status}
                washReason={bet.washReason}
                timeLeft={
                  game.phase === "play" && game.deadline
                    ? (secondsUntil(game.deadline, now) ?? 0) / rules.decisionSec
                    : null
                }
                {...people}
              />
            }
            status={
              status === "washed"
                ? "Washed: nothing was played."
                : statusLine(game, user, now, {
                    left,
                    joined,
                    full: players.length >= rules.maxPlayers,
                    canBet,
                  })
            }
            controls={controls}
          />
        )}

        <TicketFooter
          table={table}
          preview={preview}
          stats={[
            { label: "Ante", value: bet.forFun ? "4fun" : formatCents(bet.wagerCents) },
            // Who's in the game: the joined players, then the dealing order.
            { label: "Participants", value: game ? players.length : bet.pickCount },
          ]}
        />
      </div>
    </article>
  );
}

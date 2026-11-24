import { secondsUntil, signed } from "@/features/bets/cards";
import { useEffect, useRef, useState } from "react";
import { Club } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import { canBetAt, type Table, type TableSummary } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { formatClock, secondsLeft, ticketStatus } from "@/features/bets/ticket-model";
import {
  STREET_LABEL,
  holdemPlayers,
  holdemRulesFromConfig,
  positionOf,
  type HoldemGame,
  type HoldemMove,
} from "@/features/bets/holdem";
import { useHoldemGame } from "@/features/bets/holdem-game";
import { TicketByline } from "./ticket-byline";
import type { BetActions } from "./bet-controls";
import { TicketFooter } from "./ticket-footer";
import { Moves, PreMoves, RaisePanel } from "./holdem-controls";
import {
  Card,
  CardTicketHeader,
  Deck,
  FaceDown,
  Felt,
  FELT_OUTLINE,
  FeltNote,
  FeltRow,
  JoinControls,
  type People,
  PlayerName,
  Tile,
  TimerRing,
  RunItBack,
} from "./card-ticket-shell";

/** The deck and the five board cards: face down before the flop, then three, four and five turned over. */
export function Board({ board }: { board: string[] }) {
  return (
    <div
      className="flex items-center gap-1.5"
      aria-label={`Board: ${board.join(" ") || "no cards yet"}`}
    >
      <Deck />
      <span className="w-0.5" />
      {Array.from({ length: 5 }, (_, i) =>
        board[i] ? (
          <Card key={`${i}-${board[i]}`} code={board[i]} small />
        ) : (
          <FaceDown key={i} small />
        ),
      )}
    </div>
  );
}

/** Someone at the hand: position, stack, and whether they're to act, folded, all in or betting. */
function SeatTile({
  game,
  seat,
  timeLeft,
  ...people
}: People & {
  game: HoldemGame;
  seat: NonNullable<HoldemGame["seats"]>[number];
  /** The share of the turn timer left, for whoever's to act. */
  timeLeft: number | null;
}) {
  const turn = game.toActId === seat.userId;
  const position = positionOf(game, seat.userId);
  return (
    <li
      data-to-act={turn || undefined}
      className={cn(
        "flex w-24 shrink-0 snap-start flex-col items-center justify-center gap-0.5 rounded-lg border border-white/10 bg-black/20 p-1.5 text-center",
        turn && "border-primary/70 bg-primary/10",
        seat.folded && "opacity-50",
      )}
    >
      <span className="relative">
        {turn && timeLeft !== null ? (
          <TimerRing fraction={timeLeft}>
            <PersonAvatar
              id={seat.userId}
              size="sm"
              user={people.user}
              expire={people.expire}
              queryKey={people.peopleKey}
            />
          </TimerRing>
        ) : (
          <span className="inline-flex p-1">
            <PersonAvatar
              id={seat.userId}
              size="sm"
              user={people.user}
              expire={people.expire}
              queryKey={people.peopleKey}
            />
          </span>
        )}
        {position && (
          <span className="absolute -bottom-1 left-1/2 -translate-x-1/2 whitespace-nowrap rounded bg-white px-1 font-mono text-[8px] font-black text-zinc-900">
            {position}
          </span>
        )}
      </span>
      <span className="mt-1 w-full truncate text-[11px] font-semibold text-white">
        <PlayerName id={seat.userId} {...people} />
      </span>
      <span className="font-mono text-[10px] font-bold text-white/80">
        {formatCents(game.stacks?.[seat.userId] ?? 0)}
      </span>
      <span
        className={cn(
          "w-full truncate font-mono text-[9px] font-bold uppercase",
          turn ? "animate-pulse text-primary" : "text-white/60",
        )}
      >
        {turn
          ? "To act"
          : seat.folded
            ? "Folded"
            : seat.allIn
              ? "All in"
              : seat.committed > 0
                ? `Bet ${formatCents(seat.committed)}`
                : " "}
      </span>
    </li>
  );
}

/**
 * The people row for each phase, the same height and scrolling sideways: who's in while the join window is open; in
 * a hand your cards first, then every seat (the one to act slides into view); between hands everyone's result and
 * the hands shown at a showdown; at the end the standings.
 */
function PeopleRow({
  game,
  status,
  washReason,
  timeLeft,
  ...people
}: People & {
  game: HoldemGame;
  status: ReturnType<typeof ticketStatus>;
  washReason: string | undefined;
  timeLeft: number | null;
}) {
  const { user } = people;
  const row = useRef<HTMLDivElement>(null);
  // Keep whoever's to act in view.
  useEffect(() => {
    const box = row.current;
    const tile = box?.querySelector<HTMLElement>("[data-to-act]");
    if (!box || !tile) return;
    const left = tile.offsetLeft - box.offsetLeft;
    const list = box.firstElementChild as HTMLElement | null;
    if (list?.scrollTo)
      list.scrollTo({
        left: left - list.clientWidth / 2 + tile.clientWidth / 2,
        behavior: "smooth",
      });
  }, [game.toActId, game.hand]);

  if (status === "washed") return <FeltNote>{washReason ?? "Washed"}</FeltNote>;
  if (game.phase === "waiting") {
    return (
      <FeltRow label="Players">
        {holdemPlayers(game).map((id) => (
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
    const hand = game.history?.at(-1);
    if (!hand) return null;
    const winners = new Set(hand.pots.flatMap((pot) => pot.winners));
    return (
      <FeltRow label="Hand results">
        {Object.entries(hand.deltas).map(([id, delta]) => (
          <li
            key={id}
            className={cn(
              "flex w-28 shrink-0 snap-start flex-col items-center justify-center gap-1 rounded-lg border border-white/10 bg-black/20 p-1.5 text-center",
              winners.has(id) && "border-primary/70 bg-primary/10",
            )}
          >
            <span className="w-full truncate text-[11px] font-semibold text-white">
              <PlayerName id={id} {...people} />
            </span>
            {hand.shown[id] ? (
              <span className="flex -space-x-2">
                {hand.shown[id].map((c) => (
                  <Card key={c} code={c} tiny />
                ))}
              </span>
            ) : (
              <span className="h-8" />
            )}
            <span className="w-full truncate text-[9px] text-white/70">
              {hand.handNames[id] ?? (winners.has(id) ? "Everyone folded" : " ")}
            </span>
            <span
              className={cn(
                "font-mono text-[10px] font-bold",
                delta > 0 ? "text-win" : delta < 0 ? "text-loss" : "text-white/70",
              )}
            >
              {delta === 0 ? "±0" : signed(delta)}
            </span>
          </li>
        ))}
      </FeltRow>
    );
  }
  const seats = game.seats ?? [];
  return (
    <div ref={row} className="h-full">
      <FeltRow label="Seats">
        {game.me && (
          <li className="flex shrink-0 snap-start flex-col items-center justify-center gap-1 rounded-lg border border-white/15 bg-black/30 px-2">
            <span className="flex -space-x-2">
              {game.me.hole.map((c, i) => (
                <Card key={`${game.hand}-${i}-${c}`} code={c} from={{ x: -40, y: -60 }} />
              ))}
            </span>
            <span className="max-w-[6rem] truncate font-mono text-[9px] font-bold uppercase text-white/70">
              {game.me.handName ?? "Your hand"}
            </span>
          </li>
        )}
        {seats.map((seat) => (
          <SeatTile key={seat.userId} game={game} seat={seat} timeLeft={timeLeft} {...people} />
        ))}
      </FeltRow>
    </div>
  );
}

function statusLine(
  game: HoldemGame,
  user: User,
  now: number,
  join: { left: number; joined: boolean; full: boolean; canBet: boolean },
  toActName: string,
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
  if (game.phase === "results") {
    if (game.rebuyOffered?.includes(user.id))
      return `Out of chips. Rebuy for ${formatCents(game.buyInCents ?? 0)}?${timer}`;
    return `Hand ${game.hand} done.${left !== null ? ` Next deal in ${left}s.` : ""}`;
  }
  const street = game.street ? STREET_LABEL[game.street] : "";
  if (game.toActId === user.id) return `${street} · your move${timer}`;
  if (game.out?.includes(user.id)) return "You're out of this game.";
  return `${street} · ${toActName} to act${timer}`;
}

/**
 * A Hold'em bet as a ticket. The felt keeps the same regions in every phase (owner decisions, 2026-11-23): the deck,
 * board and pot; a row of people that scrolls sideways (who's in, your cards and every seat, the hand's results,
 * the standings); one status line; and your buttons (join or leave, fold / check or call / raise, the rebuy). The
 * raise amount opens over the felt. Rules, the deck audit and every hand are in Details.
 */
export function HoldemTicket({
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
  const live = !preview && (status === "active" || status === "pending");
  const query = useHoldemGame(bet, user, live, !preview);
  const now = useNow(live);
  const left = secondsLeft(bet, now);
  const blocked = !actions || actions.busy || !!actions.pending || !!actions.storageError;
  const game = query.data;
  const players = game ? holdemPlayers(game) : [];
  const joined = players.includes(user.id);
  const rules = holdemRulesFromConfig(bet.config);
  const hands = game?.hands ?? Math.max(1, players.length);
  const hand =
    !game || game.phase === "waiting" || status === "washed"
      ? 0
      : game.phase === "done"
        ? (game.history?.length ?? hands)
        : (game.hand ?? 0);
  const bigBlind = game?.bigBlindCents ?? bet.wagerCents;
  const toAct = usePerson(game?.toActId ?? bet.proposerId, user, expire, peopleKey);
  const [raising, setRaising] = useState(false);
  const myTurn = game?.phase === "play" && game.toActId === user.id && !!game.me;
  const mySeat = game?.seats?.find((s) => s.userId === user.id);
  const stillIn = game?.phase === "play" && !!mySeat && !mySeat.folded && !mySeat.allIn;
  // A raise panel left open closes when the turn moves on.
  if (raising && !myTurn) setRaising(false);
  const canBet = canBetAt(table, user.id);
  const move = (action: HoldemMove, amount?: number) =>
    actions?.start({
      kind: "holdem-act",
      tableId: table.id,
      betId: bet.id,
      action,
      ...(amount === undefined ? {} : { amount }),
    });

  const controls = !game ? null : status === "resolved" || status === "washed" ? (
    <RunItBack
      rematch={bet.rematch}
      canBet={canBet}
      disabled={blocked}
      onRun={() => actions?.start({ kind: "rematch", tableId: table.id, betId: bet.id })}
    />
  ) : status === "active" && canBet && left > 0 ? (
    <JoinControls
      joined={joined}
      full={players.length >= rules.maxPlayers}
      disabled={blocked}
      onJoin={() => actions?.start({ kind: "holdem-join", tableId: table.id, betId: bet.id })}
      onLeave={() => actions?.start({ kind: "holdem-leave", tableId: table.id, betId: bet.id })}
    />
  ) : game.phase === "results" && game.rebuyOffered?.includes(user.id) ? (
    <div className="grid h-9 grid-cols-2 gap-2">
      <Button size="sm" className="h-9" disabled={blocked} onClick={() => move("rebuy")}>
        Rebuy
      </Button>
      <Button
        size="sm"
        variant="outline"
        className={cn("h-9", FELT_OUTLINE)}
        disabled={blocked}
        onClick={() => move("pass")}
      >
        Sit out the rest
      </Button>
    </div>
  ) : stillIn && !myTurn ? (
    <PreMoves
      armed={game.me?.preMove ?? null}
      disabled={blocked}
      onArm={(next) => move(next ?? "no_pre_move")}
    />
  ) : myTurn ? (
    <Moves
      key={`${game.hand}-${game.street}-${game.currentBet}`}
      game={game}
      disabled={blocked}
      onMove={move}
      onRaise={() => setRaising(true)}
    />
  ) : null;

  const board =
    game?.phase === "play"
      ? (game.board ?? [])
      : game?.phase === "results"
        ? (game.history?.at(-1)?.board ?? [])
        : [];
  const pot =
    game?.phase === "play"
      ? (game.pot ?? 0)
      : game?.phase === "results"
        ? (game.history?.at(-1)?.pots.reduce((sum, p) => sum + p.amount, 0) ?? 0)
        : null;

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <CardTicketHeader
        icon={<Club className="size-5" />}
        name="Hold'em"
        status={status}
        joinLeft={left}
        progress={`Hand ${hand} / ${hands}`}
      />

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <TicketByline bet={bet} table={table} {...people} preview={preview} />
        {preview ? (
          <p className="text-sm text-ticket-muted">
            No-limit Texas hold'em: the button moving round, 100 big blinds to buy in and one rebuy.
            You only see your own cards until a showdown, where everyone still in shows.
          </p>
        ) : !game ? (
          <Felt
            table={<Board board={[]} />}
            people={
              <FeltNote>
                {query.isPending ? "Shuffling…" : "The game can't be shown right now."}
              </FeltNote>
            }
            status=""
          />
        ) : (
          <Felt
            table={
              <div className="flex w-full items-center justify-between gap-2">
                <Board board={board} />
                {pot !== null && (
                  <p className="shrink-0 text-right font-mono text-[10px] font-bold uppercase tracking-wider text-white/80">
                    Pot
                    <span className="block text-base text-primary">{formatCents(pot)}</span>
                  </p>
                )}
              </div>
            }
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
                : statusLine(
                    game,
                    user,
                    now,
                    {
                      left,
                      joined,
                      full: players.length >= rules.maxPlayers,
                      canBet,
                    },
                    toAct?.name ?? "…",
                  )
            }
            controls={controls}
            takeover={
              raising && myTurn ? (
                <RaisePanel
                  key={`${game.hand}-${game.street}-${game.currentBet}`}
                  game={game}
                  disabled={blocked}
                  onMove={move}
                  onClose={() => setRaising(false)}
                />
              ) : undefined
            }
          />
        )}

        <TicketFooter
          table={table}
          preview={preview}
          stats={[
            {
              label: "Blinds",
              value: bet.forFun
                ? "4fun"
                : `${formatCents(Math.floor(bigBlind / 2))}/${formatCents(bigBlind)}`,
            },
            // Who's in the game: the joined players, then the seating order.
            { label: "Participants", value: game ? players.length : bet.pickCount },
          ]}
        />
      </div>
    </article>
  );
}

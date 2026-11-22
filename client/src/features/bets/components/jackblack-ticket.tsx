import { Spade } from "lucide-react";
import { StatusBadge } from "./status-badge";
import { BetDetailsDialog } from "./bet-details";
import type { BetActions } from "./bet-controls";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { useNow } from "@/lib/use-now";
import type { Bet } from "@/features/bets/api";
import { canBetAt, type Table, type TableSummary } from "@/features/tables";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { formatClock, secondsLeft, ticketStatus } from "@/features/bets/ticket-model";
import {
  PLAYER_LIMITS,
  deciding,
  mySeat,
  playersOf,
  rulesFromConfig,
  secondsUntil,
  undecided,
  worstCase,
  type JackblackGame,
  type JackblackSeat,
} from "@/features/bets/jackblack";
import { Card, DealerButton, JoinControls, PlayControls } from "./jackblack-parts";
import { handLabel, signed, useJackblackGame } from "@/features/bets/jackblack-game";

/** Cards in the ticket come out of the shoe at the felt's top left. */
const SHOE_FROM_DEALER = { x: -64, y: 0 };
const SHOE_FROM_SEAT = { x: -48, y: -110 };
/** Seconds between cards in the opening deal. */
const DEAL_GAP = 0.14;

const BACK = "border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_3px,#7f1d1d_3px_6px)]";

interface People {
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

function Name({ id, user, expire, peopleKey }: People & { id: string }) {
  const person = usePerson(id, user, expire, peopleKey);
  return <>{id === user.id ? "You" : (person?.name ?? "…")}</>;
}

/** One seat on the ticket's felt: name, whether they've chosen yet (never what), and their hands. */
function Spot({
  seat,
  order,
  seats,
  ...people
}: People & { seat: JackblackSeat; order: number; seats: number }) {
  const { user, expire, peopleKey } = people;
  const thinking = seat.deciding === true;
  return (
    <li
      className={cn(
        "min-w-0 space-y-1.5 rounded-lg border border-white/10 bg-black/20 p-2",
        seat.userId === user.id && thinking && "border-primary/70 bg-primary/10",
      )}
    >
      <div className="flex items-center gap-1.5 text-[11px]">
        <PersonAvatar id={seat.userId} size="xs" user={user} expire={expire} queryKey={peopleKey} />
        <span className="min-w-0 flex-1 truncate font-semibold text-white">
          <Name id={seat.userId} {...people} />
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
      <div className="flex flex-wrap gap-1.5">
        {seat.hands.map((h, i) => (
          <div
            key={i}
            className={cn("space-y-1 rounded-md p-1", h.current && "ring-2 ring-primary")}
          >
            <div className="flex -space-x-2">
              {h.cards.map((code, c) => (
                <Card
                  key={`${c}-${code}`}
                  code={code}
                  small
                  from={SHOE_FROM_SEAT}
                  delay={
                    h.cards.length <= 2 && seat.hands.length === 1
                      ? (c * (seats + 1) + order) * DEAL_GAP
                      : 0
                  }
                />
              ))}
            </div>
            <p className="font-mono text-[10px] font-bold text-white/80">
              {handLabel(h, seat.hands.length > 1)}
              {h.doubled && " · 2×"}
            </p>
          </div>
        ))}
      </div>
    </li>
  );
}

/** Everyone's running result, best first. */
function Standings({ game, ...people }: People & { game: JackblackGame }) {
  const totals = Object.entries(game.totals ?? {}).sort(([, a], [, b]) => b - a);
  return (
    <ul className="divide-y divide-white/10 rounded-lg bg-black/20 px-2" aria-label="Standings">
      {totals.map(([id, cents]) => (
        <li key={id} className="flex items-center gap-1.5 py-1.5 text-[11px] text-white">
          <PersonAvatar
            id={id}
            size="xs"
            user={people.user}
            expire={people.expire}
            queryKey={people.peopleKey}
          />
          <span className="min-w-0 flex-1 truncate font-semibold">
            <Name id={id} {...people} />
          </span>
          <span
            className={cn(
              "font-mono font-bold",
              cents > 0 ? "text-win" : cents < 0 ? "text-loss" : "text-white/70",
            )}
          >
            {cents === 0 ? "Even" : signed(cents)}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** What's happening, in one line under the felt. */
function statusLine(game: JackblackGame, user: User, now: number, joinLeft: number) {
  const players = playersOf(game);
  const left = secondsUntil(game.deadline, now);
  const timer = left !== null ? ` · ${left}s` : "";
  if (game.phase === "waiting") {
    const clock = formatClock(Math.max(0, joinLeft));
    return players.includes(user.id)
      ? `You're in. Cards come out in ${clock}.`
      : `Join before the cards come out (${clock}).`;
  }
  if (game.phase === "done") {
    const mine = game.totals?.[user.id];
    if (mine === undefined) return "Game over.";
    return mine === 0
      ? "You came out even."
      : `You ${mine > 0 ? "won" : "lost"} ${formatCents(Math.abs(mine))}.`;
  }
  const dealing = game.dealerId === user.id;
  if (game.phase === "dealer")
    return dealing ? `Your turn to deal${timer}.` : "The dealer's playing their hand…";
  if (deciding(game, user.id)) return `Your move${timer}. Cards come out once everyone chooses.`;
  if (dealing) return `You're dealing. ${undecided(game)} still deciding${timer}.`;
  return `${undecided(game)} still deciding${timer}.`;
}

/**
 * A Jackblack bet as a ticket: the round's dealer and their cards, every seat's hands, and your controls (join
 * while the join window is open, then hit / stand / double / split each step, and the dealer's button when it's
 * your round to deal). The game refreshes with the table's bet feed, and every few seconds while it's played.
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
  const seats = game?.seats ?? [];
  const lastRound = game?.history?.at(-1);
  const start = (intent: Parameters<BetActions["start"]>[0]) => {
    actions?.start(intent);
  };

  return (
    <article className={cn("ticket w-full max-w-md overflow-hidden", className)}>
      <header className="flex min-h-19 items-center gap-3 py-3 pl-4 pr-10">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
          <Spade className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <p className="font-display text-lg font-extrabold uppercase leading-none">Jackblack</p>
            <StatusBadge status={status} />
            {status === "active" && (
              <span
                className={cn("scoreboard text-lg leading-none", left <= 15 && "text-loss")}
                aria-label="Time left to join"
              >
                {left > 0 ? formatClock(left) : "Dealing…"}
              </span>
            )}
          </div>
          <p className="mt-1 line-clamp-2 font-mono text-[11px] leading-tight text-ticket-muted">
            Ante {formatCents(bet.wagerCents)} · {rules.decks} deck{rules.decks === 1 ? "" : "s"} ·
            pays {rules.blackjackPays} · {rules.rotations}× round the table
          </p>
        </div>
      </header>

      <div className="perforation mx-4" />

      <div className="space-y-4 px-4 pb-4 pt-4">
        <div className="flex items-start justify-between gap-2">
          <p className="flex min-w-0 items-center gap-1.5 text-xs text-ticket-muted">
            {game?.round !== undefined && game.phase !== "done" ? (
              <span className="font-mono font-bold uppercase tracking-wider">
                Round {game.round} of {game.rounds}
              </span>
            ) : (
              <>
                <span className="shrink-0">{players.length || 1} in</span>
                <span>· everyone deals once per rotation</span>
              </>
            )}
          </p>
          <BetDetailsDialog bet={bet} table={table} {...people} disabled={preview} />
        </div>
        {bet.forFun && (
          <span className="-mt-2 inline-block rounded-full bg-ticket-foreground px-2 py-0.5 font-mono text-[10px] font-bold uppercase text-ticket">
            4fun
          </span>
        )}

        {preview ? (
          <p className="text-sm text-ticket-muted">
            Everyone who joins deals once per rotation, in a random order, and banks that round
            against the rest. Choices are made together: new cards come out once everyone has
            chosen.
          </p>
        ) : query.isPending ? (
          <p className="text-sm text-ticket-muted">Shuffling…</p>
        ) : !game ? (
          <p className="text-sm text-ticket-muted">The game can't be shown right now.</p>
        ) : (
          <>
            <div className="space-y-3 rounded-xl bg-[radial-gradient(ellipse_at_top,#1f6b3a,#0f3d22)] p-3 shadow-inner">
              {game.phase === "waiting" || status === "washed" ? (
                <ul className="flex flex-wrap gap-2" aria-label="Players">
                  {status === "washed" ? (
                    <li className="w-full py-2 text-center text-xs text-white/60">
                      {bet.washReason}
                    </li>
                  ) : (
                    players.map((id) => (
                      <li
                        key={id}
                        className="flex items-center gap-1.5 rounded-full bg-black/25 py-1 pl-1 pr-2.5 text-[11px] font-semibold text-white"
                      >
                        <PersonAvatar
                          id={id}
                          size="xs"
                          user={user}
                          expire={expire}
                          queryKey={peopleKey}
                        />
                        <Name id={id} {...people} />
                      </li>
                    ))
                  )}
                </ul>
              ) : game.phase === "done" ? (
                <Standings game={game} {...people} />
              ) : (
                <>
                  <div className="flex items-center gap-3">
                    <span className="relative block h-14 w-10 shrink-0" aria-hidden>
                      <span
                        className={cn(
                          "absolute inset-0 translate-x-1 translate-y-1 rounded-md border shadow",
                          BACK,
                        )}
                      />
                      <span className={cn("absolute inset-0 rounded-md border shadow-md", BACK)} />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex -space-x-3">
                        {(game.dealer ?? []).map((code, c) => (
                          <Card
                            key={`${game.round}-${c}-${code}`}
                            code={code}
                            // Face down, cards slide from the shoe in deal order; turned over, the hole card flips.
                            from={revealed && c === 1 ? undefined : SHOE_FROM_DEALER}
                            delay={
                              !revealed ? (c * (seats.length + 1) + seats.length) * DEAL_GAP : 0
                            }
                          />
                        ))}
                      </div>
                      <p className="mt-1 flex items-center gap-1 font-mono text-[10px] font-bold uppercase tracking-wider text-white/80">
                        Dealer ·{" "}
                        <span className="truncate">
                          {game.dealerId && <Name id={game.dealerId} {...people} />}
                        </span>
                        {game.dealerTotal !== undefined &&
                          ` · ${game.dealerTotal > 21 ? "bust" : game.dealerTotal}`}
                      </p>
                    </div>
                  </div>
                  <ul className="grid grid-cols-2 gap-2" aria-label="Seats">
                    {seats.map((seat, order) => (
                      <Spot
                        key={`${game.round}-${seat.userId}`}
                        seat={seat}
                        order={order}
                        seats={seats.length}
                        {...people}
                      />
                    ))}
                  </ul>
                </>
              )}
            </div>

            {lastRound && game.phase !== "done" && (
              <p className="font-mono text-[11px] text-ticket-muted">
                Round {lastRound.number}: <Name id={lastRound.dealerId} {...people} /> dealt{" "}
                {lastRound.dealerTotal > 21 ? "a bust" : lastRound.dealerTotal},{" "}
                {lastRound.dealerDeltaCents === 0 ? "even" : signed(lastRound.dealerDeltaCents)}
              </p>
            )}

            <p className="text-sm font-semibold" aria-live="polite">
              {statusLine(game, user, now, left)}
            </p>

            {status === "active" && canBetAt(table, user.id) && left > 0 && (
              <JoinControls
                joined={joined}
                proposer={bet.proposerId === user.id}
                full={players.length >= PLAYER_LIMITS.max}
                disabled={blocked}
                forFun={bet.forFun}
                worstCaseCents={worstCase(rules, bet.wagerCents, players.length + 1)}
                onJoin={() => start({ kind: "jackblack-join", tableId: table.id, betId: bet.id })}
                onLeave={() => start({ kind: "jackblack-leave", tableId: table.id, betId: bet.id })}
              />
            )}
            {mine && game.phase === "play" && mine.deciding !== undefined && (
              <PlayControls
                key={`${game.round}-${mine.hands.map((h) => h.cards.length).join(".")}`}
                seat={mine}
                disabled={blocked}
                onMove={(action) =>
                  start({ kind: "jackblack-act", tableId: table.id, betId: bet.id, action })
                }
              />
            )}
            {game.dealerId === user.id && game.dealerMove && (
              <DealerButton
                move={game.dealerMove}
                disabled={blocked}
                onTap={() =>
                  start({ kind: "jackblack-act", tableId: table.id, betId: bet.id, action: "deal" })
                }
              />
            )}
          </>
        )}
      </div>
    </article>
  );
}

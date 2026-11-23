import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonAvatar, PersonName } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import {
  playersOf,
  rulesFromConfig,
  worstCase,
  type JackblackGame,
} from "@/features/bets/jackblack";
import { RESULT, useJackblackGame } from "@/features/bets/jackblack-game";
import { auditGame } from "@/features/bets/card-audit";

/** Jackblack's sections in the Details dialog, in place of the pick-mode facts and the picks list. */

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 py-1.5 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="break-words text-right font-medium">{children}</span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-1">
      <h4 className="font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
        {title}
      </h4>
      <div className="divide-y rounded-lg border bg-surface px-3">{children}</div>
    </section>
  );
}

interface Props {
  bet: Bet;
  table: TableRef;
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/** The game at a glance and its rules. */
export function JackblackFacts({
  bet,
  table,
  user,
  expire,
  peopleKey,
  onNavigate,
}: Props & { onNavigate?: (() => void) | undefined }) {
  const rules = rulesFromConfig(bet.config);
  const status = ticketStatus(bet);
  const game = useJackblackGame(bet, user, status === "active" || status === "pending").data;
  const players = game ? playersOf(game).length : null;
  return (
    <>
      <Section title="Game">
        <Row label="Table">
          {onNavigate ? (
            <Link
              to="/tables/$tableId"
              params={{ tableId: table.id }}
              onClick={onNavigate}
              className="text-primary underline underline-offset-2"
            >
              {table.emoji} {table.name}
            </Link>
          ) : (
            <>
              {table.emoji} {table.name}
            </>
          )}
        </Row>
        <Row label="Proposed by">
          <PersonName id={bet.proposerId} user={user} expire={expire} queryKey={peopleKey} />
        </Row>
        <Row label="Ante">
          {formatCents(bet.wagerCents)} pts a hand{bet.forFun && " (4fun, no points)"}
        </Row>
        <Row label="Players">
          {players === null
            ? `${rules.minPlayers}–${rules.maxPlayers}`
            : `${players} of ${rules.maxPlayers} (at least ${rules.minPlayers})`}
        </Row>
        {game?.rounds !== undefined && <Row label="Rounds">{game.rounds}</Row>}
        {!bet.forFun && players !== null && players >= 2 && (
          <Row label="Worst case each">
            {formatCents(worstCase(rules, bet.wagerCents, players))} pts
          </Row>
        )}
      </Section>

      <Section title="Rules">
        <Row label="Dealing">
          Everyone deals {rules.rotations === 1 ? "once" : `${rules.rotations} times`}, random order
        </Row>
        <Row label="Shoe">Fresh every round, 1–8 decks at random, hidden</Row>
        <Row label="Blackjack pays">{rules.blackjackPays}</Row>
        <Row label="Dealer">Draws to 17, stands on soft 17</Row>
        <Row label="Double down">{rules.doubling ? "Any two cards, after splits too" : "Off"}</Row>
        <Row label="Splits">
          {rules.maxSplits === 0 ? "None" : `Up to ${rules.maxSplits}; aces get one card each`}
        </Row>
        <Row label="Decision timer">{rules.decisionSec}s, then you stand</Row>
      </Section>

      {game && (game.shuffles?.length ?? 0) > 0 && <ShuffleAudit game={game} />}
    </>
  );
}

/**
 * Each round's shoe fingerprint, shown from the deal. Once the game is over the server reveals each round's deck
 * count and seed, and this checks them here in your browser: the fingerprint matches, and the cards dealt are the
 * top of that shoe.
 */
function ShuffleAudit({ game }: { game: JackblackGame }) {
  const done = game.phase === "done";
  const audit = useQuery({
    queryKey: ["card-audit", game.shuffles, game.history?.length],
    queryFn: () => auditGame(game),
    enabled: done,
    staleTime: Infinity,
    retry: false,
  });
  const result = (round: number) => audit.data?.find((r) => r.round === round);
  return (
    <Section title="Shuffle audit">
      <p className="py-1.5 text-xs text-muted-foreground">
        {done
          ? "Each round's hidden shoe, revealed now that the game is over and checked here against the fingerprint shown from the deal and the cards dealt."
          : "Each round's shoe is fixed at the deal and hidden. Its fingerprint is here now; the deck count and seed are revealed once the game is over, so anyone can check them."}
      </p>
      {(game.shuffles ?? []).map((shuffle) => {
        const checked = result(shuffle.round);
        const ok = checked?.committed && checked.dealt;
        return (
          <Row key={shuffle.round} label={`Round ${shuffle.round}`}>
            <span className="block font-mono text-xs" title={shuffle.commitment}>
              #{shuffle.commitment.slice(0, 16)}…
            </span>
            {shuffle.decks !== undefined && (
              <span className="block text-xs text-muted-foreground">
                {shuffle.decks} deck{shuffle.decks === 1 ? "" : "s"}
                {checked === undefined ? (
                  audit.isError ? (
                    " · couldn't check"
                  ) : (
                    " · checking…"
                  )
                ) : (
                  <span className={cn("font-semibold", ok ? "text-win" : "text-loss")}>
                    {ok
                      ? " · checks out"
                      : !checked.committed
                        ? " · doesn't match its fingerprint"
                        : " · cards don't match the shoe"}
                  </span>
                )}
              </span>
            )}
          </Row>
        );
      })}
    </Section>
  );
}

/** Each round: who dealt, their hand, and every seat's result; then each player's total. */
export function JackblackPlayers({ bet, user, expire, peopleKey }: Props) {
  const status = ticketStatus(bet);
  const game = useJackblackGame(bet, user, status === "active" || status === "pending").data;
  const players = game ? playersOf(game) : [];
  const totals = game?.totals ?? {};
  const name = (id: string) => (
    <PersonName id={id} user={user} expire={expire} queryKey={peopleKey} />
  );
  return (
    <>
      <Section title={`Players (${players.length})`}>
        {players.length === 0 ? (
          <p className="py-1.5 text-sm text-muted-foreground">Nobody has joined yet.</p>
        ) : (
          players.map((id) => (
            <div key={id} className="flex items-center gap-2 py-1.5 text-sm">
              <PersonAvatar id={id} user={user} expire={expire} queryKey={peopleKey} size="xs" />
              <span className="min-w-0 flex-1 break-words">{name(id)}</span>
              {totals[id] !== undefined && <Delta cents={totals[id]} />}
            </div>
          ))
        )}
      </Section>
      {(game?.history ?? []).map((round) => (
        <Section key={round.number} title={`Round ${round.number}`}>
          <div className="flex items-center gap-2 py-1.5 text-sm">
            <span className="min-w-0 flex-1 break-words">
              {name(round.dealerId)}
              <span className="block text-xs text-muted-foreground">
                Dealer · {round.dealerTotal > 21 ? "bust" : round.dealerTotal}
              </span>
            </span>
            <Delta cents={round.dealerDeltaCents} />
          </div>
          {round.seats.map((seat) => (
            <div key={seat.userId} className="flex items-center gap-2 py-1.5 text-sm">
              <span className="min-w-0 flex-1 break-words">
                {name(seat.userId)}
                <span className="block text-xs text-muted-foreground">
                  {(seat.hands ?? [])
                    .map(
                      (h) => `${h.result ? RESULT[h.result] : h.total}${h.doubled ? " (2×)" : ""}`,
                    )
                    .join(" · ")}
                </span>
              </span>
              <Delta cents={seat.deltaCents ?? 0} />
            </div>
          ))}
        </Section>
      ))}
    </>
  );
}

function Delta({ cents }: { cents: number }) {
  return (
    <span
      className={cn(
        "w-16 text-right font-mono text-xs font-bold",
        cents > 0 ? "text-win" : cents < 0 ? "text-loss" : "text-muted-foreground",
      )}
    >
      {cents === 0 ? "Even" : formatCents(cents, true)}
    </span>
  );
}

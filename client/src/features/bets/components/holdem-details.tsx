import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import { useQuery } from "@tanstack/react-query";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonAvatar, PersonName } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import {
  BUY_IN_BIG_BLINDS,
  holdemPlayers,
  holdemRulesFromConfig,
  holdemWorstCase,
  type HoldemGame,
} from "@/features/bets/holdem";
import { useHoldemGame } from "@/features/bets/holdem-game";
import { auditHoldem } from "@/features/bets/card-audit";

/** Hold'em's sections in the Details dialog, in place of the pick-mode facts and the picks list. */

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

/** The game at a glance, its fixed rules, and the shuffle audit. */
export function HoldemFacts({
  bet,
  table,
  user,
  expire,
  peopleKey,
  onNavigate,
}: Props & { onNavigate?: (() => void) | undefined }) {
  const rules = holdemRulesFromConfig(bet.config);
  const status = ticketStatus(bet);
  const game = useHoldemGame(bet, user, status === "active" || status === "pending").data;
  const players = game ? holdemPlayers(game).length : null;
  const big = bet.forFun ? 100 : bet.wagerCents;
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
        <Row label="Blinds">
          {formatCents(Math.floor(big / 2))} / {formatCents(big)}
          {bet.forFun && " (4fun play chips)"}
        </Row>
        <Row label="Buy-in">
          {formatCents(big * BUY_IN_BIG_BLINDS)} ({BUY_IN_BIG_BLINDS} big blinds), one rebuy
        </Row>
        <Row label="Players">
          {players === null
            ? `${rules.minPlayers}–${rules.maxPlayers}`
            : `${players} of ${rules.maxPlayers} (at least ${rules.minPlayers})`}
        </Row>
        {game?.hands !== undefined && <Row label="Hands">{game.hands}</Row>}
        {!bet.forFun && (
          <Row label="Worst case each">{formatCents(holdemWorstCase(bet.wagerCents))} pts</Row>
        )}
      </Section>

      <Section title="Rules">
        <Row label="Game">No-limit Texas hold'em</Row>
        <Row label="Hands">
          One each per rotation ({rules.rotations}); the button moves one seat a hand
        </Row>
        <Row label="Rebuy">Once a game, right after busting (15s to decide)</Row>
        <Row label="Showdown">Everyone still in shows</Row>
        <Row label="Turn timer">{rules.decisionSec}s, then check if you can, else fold</Row>
        <Row label="Ties">Split; odd chips go one each to the winners at random</Row>
      </Section>

      {game && (game.shuffles?.length ?? 0) > 0 && <ShuffleAudit game={game} />}
    </>
  );
}

/**
 * Each hand's deck fingerprint from the deal. Once the game is over every deck and every hand is revealed (folded
 * ones included), and this checks each deck here in your browser: the fingerprint matches, and the cards dealt are
 * the top of that deck.
 */
function ShuffleAudit({ game }: { game: HoldemGame }) {
  const done = game.phase === "done";
  const audit = useQuery({
    queryKey: ["holdem-audit", game.shuffles, game.history?.length],
    queryFn: () => auditHoldem(game),
    enabled: done,
    staleTime: Infinity,
    retry: false,
  });
  const dealt = new Set((game.history ?? []).map((h) => h.number));
  return (
    <Section title="Shuffle audit">
      <p className="py-1.5 text-xs text-muted-foreground">
        {done
          ? "Every hand's deck, revealed now the game is over and checked here against the fingerprint shown from the deal and the cards dealt. Every hand is visible below, folded ones too."
          : "Each hand's deck is fixed at the deal and hidden. Its fingerprint is here now; once the game is over every deck is revealed so anyone can check it. That also reveals every hand, folded ones included."}
      </p>
      {(game.shuffles ?? [])
        .filter((s) => !done || dealt.has(s.round))
        .map((shuffle) => {
          const checked = audit.data?.find((r) => r.round === shuffle.round);
          const ok = checked?.committed && checked.dealt;
          return (
            <Row key={shuffle.round} label={`Hand ${shuffle.round}`}>
              <span className="block font-mono text-xs" title={shuffle.commitment}>
                #{shuffle.commitment.slice(0, 16)}…
              </span>
              {done && (
                <span className="block text-xs text-muted-foreground">
                  {checked === undefined ? (
                    audit.isError ? (
                      "couldn't check"
                    ) : (
                      "checking…"
                    )
                  ) : (
                    <span className={cn("font-semibold", ok ? "text-win" : "text-loss")}>
                      {ok
                        ? "checks out"
                        : !checked.committed
                          ? "doesn't match its fingerprint"
                          : "cards don't match the deck"}
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

/** Each player's result, then each hand: who won the pots with what, and every hand once the game is over. */
export function HoldemPlayers({ bet, user, expire, peopleKey }: Props) {
  const status = ticketStatus(bet);
  const game = useHoldemGame(bet, user, status === "active" || status === "pending").data;
  const players = game ? holdemPlayers(game) : [];
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
              <span className="min-w-0 flex-1 break-words">
                {name(id)}
                {(game?.buyIns?.[id] ?? 1) > 1 && (
                  <span className="ml-1 text-xs text-muted-foreground">(rebought)</span>
                )}
              </span>
              {game?.totals?.[id] !== undefined && <Delta cents={game.totals[id]} />}
            </div>
          ))
        )}
      </Section>
      {(game?.history ?? []).map((hand) => (
        <Section
          key={hand.number}
          title={`Hand ${hand.number} · ${hand.board.join(" ") || "no flop"}`}
        >
          {hand.pots.map((pot, i) => (
            <div key={i} className="py-1.5 text-sm">
              <span className="font-medium">{formatCents(pot.amount)}</span>{" "}
              <span className="text-muted-foreground">{i === 0 ? "pot" : "side pot"} to</span>{" "}
              {pot.winners.map((id, w) => (
                <span key={id}>
                  {w > 0 && " & "}
                  {name(id)}
                </span>
              ))}
              {pot.handName && <span className="text-muted-foreground"> · {pot.handName}</span>}
            </div>
          ))}
          {Object.entries(hand.holes ?? hand.shown).map(([id, cards]) => (
            <div key={id} className="flex items-center gap-2 py-1.5 text-sm">
              <span className="min-w-0 flex-1 break-words">
                {name(id)}
                <span className="block font-mono text-xs text-muted-foreground">
                  {cards.join(" ")}
                  {hand.handNames[id] && ` · ${hand.handNames[id]}`}
                </span>
              </span>
              <Delta cents={hand.deltas[id] ?? 0} />
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

import { Link } from "@tanstack/react-router";
import { formatCents } from "@/lib/money";
import { Delta, FactRow, FactSection, PlayerAvatar, ShuffleAudit } from "./details-parts";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonName } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import {
  STACK_BIG_BLINDS,
  holdemPlayers,
  holdemRulesFromConfig,
  holdemWorstCase,
} from "@/features/bets/holdem";
import { useHoldemGame } from "@/features/bets/holdem-game";
import { auditHoldem } from "@/features/bets/card-audit";

/** Hold'em's sections in the Details dialog, in place of the pick-mode facts and the picks list. */

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
      <FactSection title="Game">
        <FactRow label="Table">
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
        </FactRow>
        <FactRow label="Proposed by">
          <PersonName id={bet.proposerId} user={user} expire={expire} queryKey={peopleKey} />
        </FactRow>
        <FactRow label="Blinds">
          {formatCents(Math.floor(big / 2))} / {formatCents(big)}
          {bet.forFun && " (4fun play chips)"}
        </FactRow>
        <FactRow label="Stack">
          {formatCents(big * STACK_BIG_BLINDS)} ({STACK_BIG_BLINDS} big blinds), fresh every hand
        </FactRow>
        <FactRow label="Players">
          {players === null
            ? `${rules.minPlayers}–${rules.maxPlayers}`
            : `${players} of ${rules.maxPlayers} (at least ${rules.minPlayers})`}
        </FactRow>
        {game?.hands !== undefined && <FactRow label="Hands">{game.hands}</FactRow>}
        {!bet.forFun && (
          <FactRow label="Worst case each">
            {players === null
              ? `${formatCents(holdemWorstCase(bet.wagerCents, rules.minPlayers))}–${formatCents(
                  holdemWorstCase(bet.wagerCents, rules.maxPlayers),
                )}`
              : formatCents(holdemWorstCase(bet.wagerCents, players))}{" "}
            pts
          </FactRow>
        )}
      </FactSection>

      <FactSection title="Rules">
        <FactRow label="Game">No-limit Texas hold'em</FactRow>
        <FactRow label="Hands">One each; the button moves one seat a hand</FactRow>
        <FactRow label="Stacks">
          Everyone starts every hand on the same stack; nobody busts out
        </FactRow>
        <FactRow label="Showdown">Everyone still in shows</FactRow>
        <FactRow label="Turn timer">{rules.decisionSec}s, then check if you can, else fold</FactRow>
        <FactRow label="Ties">Split; odd chips go one each to the winners at random</FactRow>
      </FactSection>

      {game && (game.shuffles?.length ?? 0) > 0 && (
        <ShuffleAudit
          unit="Hand"
          intro={
            game.phase === "done"
              ? "Every hand's deck, revealed now the game is over and checked here against the fingerprint shown from the deal and the cards dealt. Every hand is visible below, folded ones too."
              : "Each hand's deck is fixed at the deal and hidden. Its fingerprint is here now; once the game is over every deck is revealed so anyone can check it. That also reveals every hand, folded ones included."
          }
          // Once it's over, only the hands that were dealt (a game can end early).
          shuffles={(game.shuffles ?? []).filter(
            (s) => game.phase !== "done" || (game.history ?? []).some((h) => h.number === s.round),
          )}
          done={game.phase === "done"}
          check={() => auditHoldem(game)}
        />
      )}
    </>
  );
}

/** Each player's result, then each hand: who won the pots with what, and every hand once the game is over. */
export function HoldemPlayers({ bet, table, user, expire, peopleKey }: Props) {
  const status = ticketStatus(bet);
  const game = useHoldemGame(bet, user, status === "active" || status === "pending").data;
  const players = game ? holdemPlayers(game) : [];
  const name = (id: string) => (
    <PersonName id={id} user={user} expire={expire} queryKey={peopleKey} />
  );
  return (
    <>
      <FactSection title={`Players (${players.length})`}>
        {players.length === 0 ? (
          <p className="py-1.5 text-sm text-muted-foreground">Nobody has joined yet.</p>
        ) : (
          players.map((id) => (
            <div key={id} className="flex items-center gap-2 py-1.5 text-sm">
              <PlayerAvatar
                id={id}
                table={table}
                user={user}
                expire={expire}
                peopleKey={peopleKey}
              />
              <span className="min-w-0 flex-1 break-words">{name(id)}</span>
              {game?.totals?.[id] !== undefined && <Delta cents={game.totals[id]} />}
            </div>
          ))
        )}
      </FactSection>
      {(game?.history ?? []).map((hand) => (
        <FactSection
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
        </FactSection>
      ))}
    </>
  );
}

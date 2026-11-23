import { Link } from "@tanstack/react-router";
import { formatCents } from "@/lib/money";
import { Delta, FactRow, FactSection, ShuffleAudit } from "./details-parts";
import type { Bet } from "@/features/bets/api";
import type { TableRef } from "@/features/tables";
import { type User, PersonAvatar, PersonName } from "@/features/people";
import { ticketStatus } from "@/features/bets/ticket-model";
import { playersOf, rulesFromConfig, worstCase } from "@/features/bets/jackblack";
import { RESULT, useJackblackGame } from "@/features/bets/jackblack-game";
import { auditGame } from "@/features/bets/card-audit";

/** Jackblack's sections in the Details dialog, in place of the pick-mode facts and the picks list. */

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
        <FactRow label="Ante">
          {formatCents(bet.wagerCents)} pts a hand{bet.forFun && " (4fun, no points)"}
        </FactRow>
        <FactRow label="Players">
          {players === null
            ? `${rules.minPlayers}–${rules.maxPlayers}`
            : `${players} of ${rules.maxPlayers} (at least ${rules.minPlayers})`}
        </FactRow>
        {game?.rounds !== undefined && <FactRow label="Rounds">{game.rounds}</FactRow>}
        {!bet.forFun && players !== null && players >= 2 && (
          <FactRow label="Worst case each">
            {formatCents(worstCase(rules, bet.wagerCents, players))} pts
          </FactRow>
        )}
      </FactSection>

      <FactSection title="Rules">
        <FactRow label="Dealing">
          Everyone deals {rules.rotations === 1 ? "once" : `${rules.rotations} times`}, random order
        </FactRow>
        <FactRow label="Shoe">Fresh every round, 1–8 decks at random, hidden</FactRow>
        <FactRow label="Blackjack pays">{rules.blackjackPays}</FactRow>
        <FactRow label="Dealer">Draws to 17, stands on soft 17</FactRow>
        <FactRow label="Double down">
          {rules.doubling ? "Any two cards, after splits too" : "Off"}
        </FactRow>
        <FactRow label="Splits">
          {rules.maxSplits === 0 ? "None" : `Up to ${rules.maxSplits}; aces get one card each`}
        </FactRow>
        <FactRow label="Decision timer">{rules.decisionSec}s, then you stand</FactRow>
      </FactSection>

      {game && (game.shuffles?.length ?? 0) > 0 && (
        <ShuffleAudit
          unit="Round"
          intro={
            game.phase === "done"
              ? "Each round's hidden shoe, revealed now that the game is over and checked here against the fingerprint shown from the deal and the cards dealt."
              : "Each round's shoe is fixed at the deal and hidden. Its fingerprint is here now; the deck count and seed are revealed once the game is over, so anyone can check them."
          }
          shuffles={game.shuffles ?? []}
          done={game.phase === "done"}
          check={() => auditGame(game)}
        />
      )}
    </>
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
      <FactSection title={`Players (${players.length})`}>
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
      </FactSection>
      {(game?.history ?? []).map((round) => (
        <FactSection key={round.number} title={`Round ${round.number}`}>
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
        </FactSection>
      ))}
    </>
  );
}

import { useState } from "react";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { type HoldemGame, type HoldemMove, type HoldemPreMove } from "@/features/bets/holdem";
import { ValueSlider } from "./form-parts";
import { FELT_OUTLINE } from "./card-ticket-shell";

/** Hold'em's controls: your moves on your turn, the raise panel, and pre-moves while you wait. */

/**
 * Your raise: takes over the felt below the board (seats, status and moves) at their exact height, so the card
 * doesn't change size. The X goes back to your moves.
 */
export function RaisePanel({
  game,
  disabled,
  onMove,
  onClose,
}: {
  game: HoldemGame;
  disabled: boolean;
  onMove: (move: HoldemMove, amount?: number) => void;
  onClose: () => void;
}) {
  const me = game.me!;
  const min = me.minRaiseTo ?? 0;
  const max = me.maxRaiseTo ?? 0;
  const [to, setTo] = useState(min);
  const raiseTo = Math.min(Math.max(to, min), max);
  const bet = (game.currentBet ?? 0) === 0;
  // A pot-sized raise: call, then raise by everything in the pot after the call.
  const toCall = me.toCall ?? 0;
  const potAfterCall = (game.pot ?? 0) + toCall;
  const clamp = (v: number) => Math.min(Math.max(Math.round(v), min), max);
  const quick = [
    { label: "Min", to: min },
    { label: "½ Pot", to: clamp((game.currentBet ?? 0) + potAfterCall / 2) },
    { label: "Pot", to: clamp((game.currentBet ?? 0) + potAfterCall) },
    { label: "All in", to: max },
  ];
  return (
    <div className="flex h-full flex-col gap-1.5">
      <div className="flex shrink-0 gap-1.5">
        {quick.map((q) => (
          <Button
            key={q.label}
            size="sm"
            variant="outline"
            className={cn(
              "h-8 min-w-0 flex-1 px-1 text-xs",
              FELT_OUTLINE,
              raiseTo === q.to && "border-primary",
            )}
            onClick={() => setTo(q.to)}
          >
            {q.label}
          </Button>
        ))}
        <Button
          size="icon"
          variant="outline"
          className={cn("size-8 shrink-0", FELT_OUTLINE)}
          onClick={onClose}
          aria-label="Cancel raise"
        >
          <X />
        </Button>
      </div>
      <div className="min-h-0 flex-1">
        <ValueSlider
          value={raiseTo}
          min={min}
          max={max}
          step={Math.max(1, game.smallBlindCents ?? 1)}
          format={(v) => formatCents(v)}
          label={bet ? "Bet" : "Raise to"}
          onChange={setTo}
        />
      </div>
      <Button
        size="sm"
        className="h-9 w-full shrink-0"
        disabled={disabled}
        onClick={() => {
          if (raiseTo >= max) onMove("all_in");
          else onMove("raise", raiseTo);
          onClose();
        }}
      >
        {raiseTo >= max ? "All in" : `${bet ? "Bet" : "Raise"} ${formatCents(raiseTo)}`}
      </Button>
    </div>
  );
}

/** Your moves on your turn, in one row: fold, check or call, and raise (or all in). */
export function Moves({
  game,
  disabled,
  onMove,
  onRaise,
}: {
  game: HoldemGame;
  disabled: boolean;
  onMove: (move: HoldemMove) => void;
  onRaise: () => void;
}) {
  const me = game.me!;
  const toCall = me.toCall ?? 0;
  const max = me.maxRaiseTo ?? 0;
  const canRaise = !!me.canRaise && max > (game.currentBet ?? 0);
  const bet = (game.currentBet ?? 0) === 0;
  return (
    <div className="grid h-9 grid-cols-3 gap-2">
      <Button
        size="sm"
        variant="outline"
        className={cn("h-9", FELT_OUTLINE)}
        disabled={disabled}
        onClick={() => onMove("fold")}
      >
        Fold
      </Button>
      <Button
        size="sm"
        variant="outline"
        className={cn("h-9", FELT_OUTLINE)}
        disabled={disabled}
        onClick={() => onMove(toCall === 0 ? "check" : "call")}
      >
        {toCall === 0 ? "Check" : `Call ${formatCents(toCall)}`}
      </Button>
      <Button
        size="sm"
        className="h-9"
        disabled={disabled || (!canRaise && toCall >= max)}
        onClick={() => (canRaise ? onRaise() : onMove("all_in"))}
      >
        {canRaise ? (bet ? "Bet…" : "Raise…") : "All in"}
      </Button>
    </div>
  );
}

/**
 * While it's not your turn: arm "Check/Fold" (check if you can, otherwise fold) or "Call any" (call whatever it is,
 * or check). It's kept on the server, which plays it the moment the action reaches you (ticket open or not), then
 * clears it; tapping it again clears it too.
 */
export function PreMoves({
  armed,
  disabled,
  onArm,
}: {
  armed: HoldemPreMove | null;
  disabled: boolean;
  onArm: (move: HoldemPreMove | null) => void;
}) {
  const toggle = (move: HoldemPreMove) => onArm(armed === move ? null : move);
  const options: [HoldemPreMove, string][] = [
    ["check_fold", "Check/Fold"],
    ["call_any", "Call any"],
  ];
  return (
    <div className="grid h-9 grid-cols-2 gap-2">
      {options.map(([move, label]) => (
        <Button
          key={move}
          size="sm"
          variant={armed === move ? "default" : "outline"}
          aria-pressed={armed === move}
          className={cn("h-9", armed !== move && FELT_OUTLINE)}
          disabled={disabled}
          onClick={() => toggle(move)}
        >
          {label}
        </Button>
      ))}
    </div>
  );
}

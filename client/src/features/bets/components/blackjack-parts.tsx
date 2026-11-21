import { useState } from "react";
import { motion, useReducedMotionConfig } from "motion/react";
import { Button } from "@/components/ui/button";
import { Stepper } from "./form-parts";
import type { BetActions } from "./bet-controls";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import {
  allowedMoves,
  cardFace,
  type BlackjackHand,
  type BlackjackMove,
  type BlackjackRules,
  type BlackjackSeat,
} from "@/features/bets/blackjack";

/** Pieces the Blackjack ticket and the table felt share: cards, the hand query, and the player's controls. */

/**
 * One playing card; "??" is face down. A new card slides in from {@code from} (pixels from where it lands, the
 * shoe on the felt); a hole card turning over flips.
 */
export function Card({
  code,
  small,
  tiny,
  from,
  delay = 0,
}: {
  code: string;
  small?: boolean;
  tiny?: boolean;
  from?: { x: number; y: number } | undefined;
  delay?: number;
}) {
  const reduced = !!useReducedMotionConfig();
  const face = cardFace(code);
  return (
    <motion.span
      initial={
        reduced
          ? false
          : from
            ? { x: from.x, y: from.y, rotate: -25, opacity: 0, scale: 0.7 }
            : { rotateY: 90, opacity: 0 }
      }
      animate={{ x: 0, y: 0, rotate: 0, rotateY: 0, opacity: 1, scale: 1 }}
      transition={{ type: "spring", stiffness: 260, damping: 26, delay }}
      className={cn(
        "inline-flex shrink-0 flex-col items-center justify-center rounded-md border font-bold leading-none shadow-md",
        tiny ? "h-8 w-6 text-[10px]" : small ? "h-10 w-7 text-xs" : "h-14 w-10 text-sm",
        face
          ? "border-black/15 bg-white"
          : "border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_3px,#7f1d1d_3px_6px)]",
        face && (face.red ? "text-red-600" : "text-zinc-900"),
      )}
      aria-label={face ? `${face.rank}${face.suit}` : "Face-down card"}
    >
      {face && (
        <>
          <span>{face.rank}</span>
          <span className={tiny ? "text-xs" : small ? "text-sm" : "text-base"}>{face.suit}</span>
        </>
      )}
    </motion.span>
  );
}

/** Sitting down while the join window is open: a bet within the dealer's limits. */
export function SeatControls({
  rules,
  mine,
  full,
  disabled,
  onSit,
  onLeave,
}: {
  rules: BlackjackRules;
  mine: BlackjackSeat | undefined;
  full: boolean;
  disabled: boolean;
  onSit: (betCents: number) => void;
  onLeave: () => void;
}) {
  const [bet, setBet] = useState(mine?.betCents ?? rules.minBetCents);
  if (full && !mine) return <p className="text-xs text-ticket-muted">Every seat is taken.</p>;
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <div className="flex-1">
          <Stepper
            value={bet}
            step={rules.minBetCents >= 100 ? 100 : 10}
            min={rules.minBetCents}
            max={rules.maxBetCents}
            label="Your bet"
            format={(v) => `${formatCents(v)} pts`}
            onChange={setBet}
          />
        </div>
        <Button
          size="sm"
          className="h-10"
          disabled={disabled || bet === mine?.betCents}
          onClick={() => onSit(bet)}
        >
          {mine ? "Change bet" : "Sit down"}
        </Button>
      </div>
      <div className="flex items-center justify-between font-mono text-[10px] uppercase tracking-wider text-ticket-muted">
        <span>
          Min {formatCents(rules.minBetCents)} · Max {formatCents(rules.maxBetCents)}
        </span>
        {mine && (
          <button
            type="button"
            className="font-bold underline-offset-2 hover:underline disabled:opacity-40"
            disabled={disabled}
            onClick={onLeave}
          >
            Leave seat
          </button>
        )}
      </div>
    </div>
  );
}

export function PlayControls({
  hand,
  seat,
  rules,
  disabled,
  onMove,
}: {
  hand: BlackjackHand;
  seat: BlackjackSeat;
  rules: BlackjackRules;
  disabled: boolean;
  onMove: (move: BlackjackMove) => void;
}) {
  if (hand.phase === "insurance") {
    return (
      <div className="grid grid-cols-2 gap-2">
        <Button size="sm" disabled={disabled} onClick={() => onMove("insurance")}>
          Insure · {formatCents(Math.floor(seat.betCents / 2))}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={disabled}
          onClick={() => onMove("no_insurance")}
        >
          No insurance
        </Button>
      </div>
    );
  }
  const can = allowedMoves(rules, seat);
  return (
    <div className="grid grid-cols-4 gap-2">
      <Button size="sm" disabled={disabled} onClick={() => onMove("hit")}>
        Hit
      </Button>
      <Button size="sm" variant="outline" disabled={disabled} onClick={() => onMove("stand")}>
        Stand
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={disabled || !can.double}
        onClick={() => onMove("double")}
      >
        Double
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={disabled || !can.split}
        onClick={() => onMove("split")}
      >
        Split
      </Button>
    </div>
  );
}

const DEALER_LABEL = { flip: "Flip", hit: "Hit", stand: "Stand" } as const;

/** The dealer's one button on their turn: flip the hole card, then hit or stand as the rules say. */
export function DealerButton({
  move,
  disabled,
  onTap,
}: {
  move: keyof typeof DEALER_LABEL;
  disabled: boolean;
  onTap: () => void;
}) {
  return (
    <Button size="sm" className="w-full" disabled={disabled} onClick={onTap}>
      {DEALER_LABEL[move]}
    </Button>
  );
}

export type StartBetAction = BetActions["start"];

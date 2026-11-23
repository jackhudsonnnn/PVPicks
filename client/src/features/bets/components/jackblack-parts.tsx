import { motion, useReducedMotionConfig } from "motion/react";
import { Button } from "@/components/ui/button";
import type { BetActions } from "./bet-controls";
import { cn } from "@/lib/utils";
import { cardFace, type JackblackChoice, type JackblackSeat } from "@/features/bets/jackblack";

/** Pieces of the Jackblack ticket: cards and the player's and dealer's controls. */

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

/**
 * Joining (or leaving) while the join window is open: one button, so the controls bar keeps its height (why you
 * can't, and what joining holds against your limit, are in the status line and Details). Nothing for the proposer,
 * who's in already.
 */
export function JoinControls({
  joined,
  proposer,
  full,
  late,
  disabled,
  onJoin,
  onLeave,
}: {
  joined: boolean;
  proposer: boolean;
  full: boolean;
  /** The last seconds before the deal: nobody joins or leaves. */
  late: boolean;
  disabled: boolean;
  onJoin: () => void;
  onLeave: () => void;
}) {
  if (proposer || late || (full && !joined)) return null;
  return (
    <Button
      size="sm"
      variant={joined ? "outline" : "default"}
      className={cn("h-9 w-full", joined && FELT_OUTLINE)}
      disabled={disabled}
      onClick={joined ? onLeave : onJoin}
    >
      {joined ? "Leave the game" : "Join the game"}
    </Button>
  );
}

const CHOICE_LABEL: Record<JackblackChoice, string> = {
  hit: "Hit",
  stand: "Stand",
  double: "Double",
  split: "Split",
};

/**
 * Your choice for this step. Nothing is dealt until everyone has chosen (or the timer runs out), so once you've
 * chosen the buttons wait with yours marked.
 */
export function PlayControls({
  seat,
  disabled,
  onMove,
}: {
  seat: JackblackSeat;
  disabled: boolean;
  onMove: (move: JackblackChoice) => void;
}) {
  const chosen = seat.choice;
  const allowed = (move: JackblackChoice) =>
    move === "double" ? !!seat.canDouble : move === "split" ? !!seat.canSplit : true;
  return (
    <div className="grid h-9 grid-cols-4 gap-2">
      {(Object.keys(CHOICE_LABEL) as JackblackChoice[]).map((move) => (
        <Button
          key={move}
          size="sm"
          variant={chosen === move || (!chosen && move === "hit") ? "default" : "outline"}
          className={cn("h-9", !(chosen === move || (!chosen && move === "hit")) && FELT_OUTLINE)}
          disabled={disabled || !!chosen || !allowed(move)}
          onClick={() => onMove(move)}
        >
          {CHOICE_LABEL[move]}
        </Button>
      ))}
    </div>
  );
}

export const CHOICE_NAMES = CHOICE_LABEL;

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
    <Button size="sm" className="h-9 w-full" disabled={disabled} onClick={onTap}>
      {DEALER_LABEL[move]}
    </Button>
  );
}

/** Outline buttons on the green felt: see-through instead of the page background. */
const FELT_OUTLINE = "border-white/25 bg-black/20 text-white hover:bg-black/35 hover:text-white";

export type StartBetAction = BetActions["start"];

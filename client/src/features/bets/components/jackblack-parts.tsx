import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { type JackblackChoice, type JackblackSeat } from "@/features/bets/jackblack";
import { FELT_OUTLINE } from "./card-ticket-shell";

/** Pieces of the Jackblack ticket: the player's and the dealer's controls. */

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

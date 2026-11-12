import { useRef } from "react";
import {
  AnimatePresence,
  motion,
  useReducedMotionConfig,
  type HTMLMotionProps,
  type Transition,
  type Variants,
} from "motion/react";
import { ChevronLeft, ChevronRight, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { getMode } from "@/features/bets/modes";
import { inThePot } from "@/features/bets/payout";
import { formatClock, formatPts } from "@/features/bets/ledger";
import { StatusBadge } from "@/features/bets/components/status-badge";
import type { Bet } from "@/features/bets/types";

type Slot = "front" | "right" | "left";

/** Card positions in the fan. zIndex animates as an integer, so cards swap layers mid-flight. */
const CARD_VARIANTS: Variants = {
  front: { x: 0, y: 0, rotate: 0, scale: 1, opacity: 1, zIndex: 3 },
  right: { x: 18, y: 0, rotate: 7, scale: 0.92, opacity: 0.85, zIndex: 2 },
  left: { x: -18, y: 0, rotate: -7, scale: 0.92, opacity: 0.85, zIndex: 1 },
  enter: (dir: number) => ({
    x: dir * 46,
    y: 6,
    rotate: dir * 16,
    scale: 0.8,
    opacity: 0,
    zIndex: 0,
  }),
  exit: (dir: number) => ({
    x: -dir * 46,
    y: 6,
    rotate: -dir * 16,
    scale: 0.8,
    opacity: 0,
    zIndex: 0,
  }),
};
const CARD_SPRING: Transition = { type: "spring", stiffness: 260, damping: 26, mass: 0.8 };

/** Open bets fanned out like cards in the middle of the felt. */
export function BetStack({
  bets,
  focusedId,
  onFocus,
  onOpen,
  onPropose,
}: {
  bets: Bet[];
  focusedId: string | undefined;
  onFocus: (id: string) => void;
  onOpen?: (id: string) => void;
  onPropose?: (() => void) | undefined;
}) {
  // Low motion: cards and seats jump straight to place instead of sliding.
  const reduced = !!useReducedMotionConfig();
  const idx = Math.max(
    0,
    bets.findIndex((b) => b.id === focusedId),
  );
  // Which way the deck moved (+1 next, -1 previous), so cards enter from one side and leave from the other.
  const dirRef = useRef(1);
  const dir = dirRef.current;
  // Set after a swipe so the click that ends it doesn't also open the ticket.
  const swipedRef = useRef(false);

  if (bets.length === 0) {
    return (
      <div className="flex flex-col items-center gap-3 text-center">
        <p className="font-display text-xl font-bold uppercase text-foreground/80">No open bets</p>
        {onPropose ? (
          <Button variant="hero" size="sm" onClick={onPropose}>
            <Plus /> Propose one
          </Button>
        ) : (
          <p className="text-xs text-foreground/60">Only hosts can start bets here</p>
        )}
      </div>
    );
  }

  const focused = bets[idx]!;
  const go = (d: number) => {
    dirRef.current = d;
    onFocus(bets[(idx + d + bets.length) % bets.length]!.id);
  };

  const players = inThePot(focused.participants).length;

  // Show the focused card on top with up to two neighbours fanned behind it.
  const behind = [1, -1]
    .map((d) => bets[(idx + d + bets.length) % bets.length]!)
    .filter((b, i, arr) => b.id !== focused.id && arr.findIndex((y) => y.id === b.id) === i);
  const cards: { bet: Bet; slot: Slot }[] = [
    { bet: focused, slot: "front" },
    ...behind.map((bet, i) => ({ bet, slot: (i === 0 ? "right" : "left") as Slot })),
  ];
  // Keep DOM order stable (zIndex handles stacking): moving nodes would cancel running animations.
  cards.sort((a, b) => bets.indexOf(a.bet) - bets.indexOf(b.bet));

  return (
    <div className="flex flex-col items-center gap-2">
      <motion.div
        className="relative h-28 w-28 touch-pan-y sm:h-40 sm:w-44"
        onPanEnd={(_, info) => {
          if (bets.length < 2) return;
          const { offset, velocity } = info;
          if (Math.abs(offset.x) < 40 && Math.abs(velocity.x) < 400) return;
          if (Math.abs(offset.x) < Math.abs(offset.y)) return;
          swipedRef.current = true;
          go(offset.x < 0 ? 1 : -1);
        }}
        onClickCapture={(e) => {
          if (swipedRef.current) {
            swipedRef.current = false;
            e.stopPropagation();
          }
        }}
      >
        <AnimatePresence initial={false} custom={dir}>
          {cards.map(({ bet, slot }) => (
            <MiniTicket
              key={bet.id}
              bet={bet}
              custom={dir}
              variants={CARD_VARIANTS}
              initial="enter"
              animate={slot}
              exit="exit"
              transition={reduced ? { duration: 0 } : CARD_SPRING}
              whileHover={slot === "front" ? { scale: 1.03 } : { opacity: 1, y: -4 }}
              focused={slot === "front"}
              onClick={() => (slot === "front" ? onOpen?.(bet.id) : go(slot === "right" ? 1 : -1))}

              aria-label={slot === "front" ? "Open ticket" : "Show this bet"}
            />
          ))}
        </AnimatePresence>
      </motion.div>

      <div className="flex items-center gap-1 rounded-full bg-felt-rim/85 px-1 py-0.5">
        {bets.length > 1 && (
          <button
            onClick={() => go(-1)}
            className="flex size-6 items-center justify-center rounded-full text-foreground hover:bg-foreground/10"
            aria-label="Previous bet"
          >
            <ChevronLeft className="size-4" />
          </button>
        )}
        <span className="whitespace-nowrap px-1.5 font-mono text-[9px] font-bold uppercase tracking-wider text-foreground sm:text-[10px]">
          {focused.status === "active" ? (
            <>{players} in</>
          ) : (
            <>
              <span className="text-primary">{formatPts(players * focused.wager)}</span>
            </>
          )}
          {bets.length > 1 && (
            <span className="text-muted-foreground">
              {" "}
              · {idx + 1}/{bets.length}
            </span>
          )}
        </span>
        {bets.length > 1 && (
          <button
            onClick={() => go(1)}
            className="flex size-6 items-center justify-center rounded-full text-foreground hover:bg-foreground/10"
            aria-label="Next bet"
          >
            <ChevronRight className="size-4" />
          </button>
        )}
      </div>
    </div>
  );
}

function MiniTicket({
  bet,
  focused,
  className,
  ...props
}: { bet: Bet; focused?: boolean } & HTMLMotionProps<"button">) {
  const mode = getMode(bet.modeId);
  const Icon = mode.icon;
  return (
    <motion.button
      type="button"
      className={cn(
        "absolute inset-0 flex flex-col rounded-lg bg-ticket p-2 text-left text-ticket-foreground shadow-ticket transition-shadow duration-300 sm:p-3",
        focused && "ring-2 ring-primary",
        className,
      )}
      {...props}
    >
      <span className="flex items-center gap-1.5">
        <Icon className="size-3.5 shrink-0" />
        <span className="truncate font-display text-xs font-extrabold uppercase">{mode.name}</span>
      </span>
      <span className="mt-1 line-clamp-3 font-display text-xs font-bold leading-tight sm:mt-1.5 sm:line-clamp-4 sm:text-base">
        {bet.question}
      </span>
      <span className="mt-auto flex items-center justify-between pt-1">
        <StatusBadge status={bet.status} className="px-1.5 py-0.5 text-[8px]" />
        {bet.status === "active" && (
          <span className={cn("scoreboard text-sm sm:text-base", bet.closesInSec <= 15 && "text-loss")}>
            {formatClock(bet.closesInSec)}
          </span>
        )}
      </span>
    </motion.button>
  );
}

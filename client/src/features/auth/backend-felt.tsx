import { useRef } from "react";
import { ChevronLeft, ChevronRight, Plus } from "lucide-react";
import {
  AnimatePresence,
  motion,
  useReducedMotionConfig,
  type HTMLMotionProps,
  type Transition,
  type Variants,
} from "motion/react";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/features/bets/components/status-badge";
import { MODES } from "@/features/bets/modes";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import type { BackendBet, BackendTable, BackendUser } from "./backend-api";
import { PersonAvatar } from "./backend-avatar";
import { arrangeSeats, seatPosition, seatStatus, type SeatTone } from "./seating";
import type { TableBalance } from "./tables-api";
import { formatClock, potCents, secondsLeft, ticketStatus } from "./ticket-model";
import { useNow } from "./use-now";
import { usePerson } from "./use-person";

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

const TONE: Record<SeatTone, string> = {
  pick: "bg-foreground text-background",
  pass: "bg-muted text-muted-foreground",
  win: "bg-win text-primary-foreground",
  loss: "bg-loss text-primary-foreground",
  washed: "bg-status-washed text-primary-foreground",
};

interface People {
  user: BackendUser;
  expire: () => void;
  peopleKey: readonly string[];
}

/**
 * The card-room table: members seated around the felt with their points at this table, and the open bets fanned
 * in the middle. Picking a seat or the "others" spot opens the Peers list.
 */
export function Felt({
  table,
  bets,
  focused,
  balances,
  onFocus,
  onOpenBet,
  onOpenPeers,
  onSelectUser,
  onPropose,
  ...people
}: People & {
  table: BackendTable;
  bets: BackendBet[];
  focused: BackendBet | undefined;
  balances: TableBalance[] | undefined;
  onFocus: (id: string) => void;
  onOpenBet: (id: string) => void;
  onOpenPeers: () => void;
  onSelectUser: (userId: string) => void;
  /** Omitted when you can't start bets here. */
  onPropose?: (() => void) | undefined;
}) {
  const { user } = people;
  const { seated, rail } = arrangeSeats(table.memberIds, user.id, focused);
  const reduced = !!useReducedMotionConfig();
  const seatTransition = { duration: reduced ? 0 : 0.18 };
  // Keep mounted nodes in table order even when your bottom seat has a different index.
  const slots = seated.map((id, index) => ({ id, index }));
  slots.sort((a, b) => table.memberIds.indexOf(a.id) - table.memberIds.indexOf(b.id));
  const seatCount = seated.length + (rail.length > 0 ? 1 : 0);
  const othersPos = seatPosition(seated.length, Math.max(seatCount, 2));

  return (
    <div className="mx-auto flex h-full w-full max-w-3xl items-center justify-center px-8 pb-12 pt-10 [container-type:size] sm:px-14 sm:pb-16 sm:pt-14">
      <div className="felt-table relative aspect-[5/6] w-[min(100cqw,calc(100cqh*5/6))] sm:aspect-[7/5] sm:w-[min(100cqw,calc(100cqh*7/5))]">
        <div className="felt-line absolute inset-[7%]" data-tomato-table />
        <span className="pointer-events-none absolute left-1/2 top-[16%] -translate-x-1/2 font-display text-xs font-black uppercase tracking-[0.3em] text-foreground/15 sm:text-sm">
          P2Picks
        </span>
        <div className="absolute inset-0 flex items-center justify-center">
          <BetStack
            bets={bets}
            focusedId={focused?.id}
            onFocus={onFocus}
            onOpen={onOpenBet}
            onPropose={onPropose}
          />
        </div>
        {slots.map(({ id, index }) => {
          const pos = seatPosition(index, Math.max(seatCount, 2));
          return (
            <motion.div
              key={id}
              className="absolute z-10"
              style={{ x: "-50%", y: "-50%", left: `${pos.left}%`, top: `${pos.top}%` }}
              initial={reduced ? false : { opacity: 0, scale: 0.85 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={seatTransition}
            >
              <Seat
                id={id}
                focused={focused}
                balance={balances?.find((b) => b.userId === id)}
                onClick={() => onSelectUser(id)}
                {...people}
              />
            </motion.div>
          );
        })}
        {rail.length > 0 && (
          <motion.button
            key="others"
            data-tomato-others={JSON.stringify(rail)}
            onClick={onOpenPeers}
            className="group absolute z-10 flex w-16 flex-col items-center sm:w-20"
            style={{ x: "-50%", y: "-50%", left: `${othersPos.left}%`, top: `${othersPos.top}%` }}
            initial={reduced ? false : { opacity: 0, scale: 0.85 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={seatTransition}
            aria-label={`${rail.length} other${rail.length === 1 ? "" : "s"} at this table`}
          >
            <span className="flex size-8 items-center justify-center rounded-full border-2 border-dashed border-foreground/40 bg-felt-rim font-display text-xs font-black transition-transform group-hover:scale-105 sm:size-10 sm:text-sm">
              +{rail.length}
            </span>
            <span className="relative mt-0.5 w-full rounded-md border bg-felt-rim px-1 py-0.5 text-center shadow-lg">
              <span className="block truncate text-[10px] font-semibold leading-tight sm:text-[11px]">
                {rail.length} other{rail.length === 1 ? "" : "s"}
              </span>
              <span className="block font-mono text-[9px] leading-tight text-muted-foreground sm:text-[10px]">
                Tap to view
              </span>
            </span>
          </motion.button>
        )}
      </div>
    </div>
  );
}

function Seat({
  id,
  focused,
  balance,
  onClick,
  ...people
}: People & {
  id: string;
  focused: BackendBet | undefined;
  balance: TableBalance | undefined;
  onClick: () => void;
}) {
  const { user, expire, peopleKey } = people;
  const person = usePerson(id, user, expire, peopleKey);
  const chip = seatStatus(focused, id, user.id);
  const name = person?.name ?? "…";
  const settle = balance?.settleCents;
  return (
    <button
      type="button"
      onClick={onClick}
      data-tomato-user={id}
      className="group flex w-16 flex-col items-center sm:w-20"
      aria-label={`${name}'s profile`}
    >
      <span className="relative">
        <PersonAvatar
          id={id}
          size="md"
          className={cn(
            "size-8 text-xs ring-felt-rim transition-transform group-hover:scale-105 sm:size-10 sm:text-sm",
            id === user.id && "ring-primary",
          )}
          user={user}
          expire={expire}
          queryKey={peopleKey}
        />
        {focused?.proposerId === id && (
          <span
            className="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-foreground font-display text-[9px] font-black text-background"
            title="Proposer"
          >
            P
          </span>
        )}
      </span>
      <span className="relative mt-0.5 w-full rounded-md border bg-felt-rim px-1 py-0.5 text-center shadow-lg">
        <span className="block truncate text-[10px] font-semibold leading-tight sm:text-[11px]">
          {name}
        </span>
        <span
          className={cn(
            "block font-mono text-[9px] leading-tight sm:text-[10px]",
            settle === undefined || settle === 0n
              ? "text-muted-foreground"
              : settle > 0n
                ? "text-win"
                : "text-loss",
          )}
          title="Points at this table that the next settlement moves"
        >
          {settle === undefined ? "–" : formatCents(settle, true)}
        </span>
      </span>
      {chip && (
        <span
          className={cn(
            "mt-0.5 max-w-full truncate rounded-full px-1.5 py-px font-mono text-[9px] font-bold uppercase",
            TONE[chip.tone],
          )}
        >
          {chip.label}
        </span>
      )}
    </button>
  );
}

/** Open bets fanned out like cards in the middle of the felt. */
function BetStack({
  bets,
  focusedId,
  onFocus,
  onOpen,
  onPropose,
}: {
  bets: BackendBet[];
  focusedId: string | undefined;
  onFocus: (id: string) => void;
  onOpen: (id: string) => void;
  onPropose?: (() => void) | undefined;
}) {
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
          <p className="text-xs text-foreground/60">You can't start bets here</p>
        )}
      </div>
    );
  }

  const focused = bets[idx]!;
  const go = (d: number) => {
    dirRef.current = d;
    onFocus(bets[(idx + d + bets.length) % bets.length]!.id);
  };
  const pot = potCents(focused);
  const behind = [1, -1]
    .map((d) => bets[(idx + d + bets.length) % bets.length]!)
    .filter((b, i, arr) => b.id !== focused.id && arr.findIndex((y) => y.id === b.id) === i);
  const cards: { bet: BackendBet; slot: Slot }[] = [
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
              onClick={() => (slot === "front" ? onOpen(bet.id) : go(slot === "right" ? 1 : -1))}
              aria-label={slot === "front" ? `Open ticket: ${bet.question}` : "Show this bet"}
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
          {pot === null ? (
            <>{focused.pickCount} in</>
          ) : (
            <span className="text-primary">{focused.forFun ? "4fun" : formatCents(pot)}</span>
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
}: { bet: BackendBet; focused?: boolean } & HTMLMotionProps<"button">) {
  const mode = MODES.find((m) => m.id === bet.modeId)!;
  const Icon = mode.icon;
  const status = ticketStatus(bet);
  const now = useNow(status === "active");
  const left = secondsLeft(bet, now);
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
      <span className="mt-1 line-clamp-3 break-words font-display text-xs font-bold leading-tight sm:mt-1.5 sm:line-clamp-4 sm:text-base">
        {bet.question}
      </span>
      <span className="mt-auto flex items-center justify-between pt-1">
        <StatusBadge status={status} className="px-1.5 py-0.5 text-[8px]" />
        {status === "active" && (
          <span className={cn("scoreboard text-sm sm:text-base", left <= 15 && "text-loss")}>
            {left > 0 ? formatClock(left) : "…"}
          </span>
        )}
      </span>
    </motion.button>
  );
}

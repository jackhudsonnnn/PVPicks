import type { ReactNode } from "react";
import { motion, useReducedMotionConfig } from "motion/react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { type User, PersonAvatar, usePerson } from "@/features/people";
import { cardFace } from "@/features/bets/cards";
import { formatClock, type ticketStatus } from "@/features/bets/ticket-model";
import { StatusBadge } from "./status-badge";

/**
 * The frame both card-game tickets (Jackblack, Hold'em) share (owner decisions, 2026-11-23): a header with the game's
 * progress, and a felt with the same fixed regions in every phase, so the card keeps its height from the join window
 * to the final standings. Anything wider than the felt scrolls sideways (`FeltRow`), never down.
 */

export function CardTicketHeader({
  icon,
  name,
  status,
  joinLeft,
  progress,
}: {
  icon: ReactNode;
  name: string;
  status: ReturnType<typeof ticketStatus>;
  /** Seconds left to join (shown while the join window is open). */
  joinLeft: number;
  /** "Round 2 / 7", "Hand 0 / 4". */
  progress: string;
}) {
  return (
    <header className="flex min-h-19 items-center gap-3 py-3 pl-4 pr-10">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-ticket-foreground text-ticket">
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <p className="font-display text-lg font-extrabold uppercase leading-none">{name}</p>
          <StatusBadge status={status} />
          {status === "active" && (
            <span
              className={cn("scoreboard text-lg leading-none", joinLeft <= 15 && "text-loss")}
              aria-label="Time left to join"
            >
              {formatClock(Math.max(0, joinLeft))}
            </span>
          )}
        </div>
        <p className="mt-1 font-mono text-[11px] font-bold uppercase tracking-wider text-ticket-muted">
          {progress}
        </p>
      </div>
    </header>
  );
}

const FELT = "rounded-xl bg-[radial-gradient(ellipse_at_top,#1f6b3a,#0f3d22)] p-3 shadow-inner";

/**
 * The felt's four regions, each a fixed height: the table (the dealer's hand or the board, with the deck), a row of
 * people (seats, players, results or standings, scrolling sideways), one status line, and the controls (an empty
 * bar when there's nothing to press). `takeover` replaces those last three with one panel of exactly their combined
 * height (Hold'em's raise), so the card never changes height.
 */
export function Felt({
  table,
  people,
  status,
  controls,
  takeover,
}: {
  table: ReactNode;
  people: ReactNode;
  status: ReactNode;
  controls?: ReactNode;
  takeover?: ReactNode;
}) {
  return (
    <div className={cn("relative grid gap-2", FELT)}>
      <div className="flex h-[5.5rem] items-center">{table}</div>
      {takeover ? (
        // People (7.5rem) + gap + status (1px rule, 0.5rem padding, 1.25rem line) + gap + controls (2.25rem).
        <div className="h-[calc(12.5rem+1px)] min-w-0 overflow-hidden">{takeover}</div>
      ) : (
        <>
          <div className="h-[7.5rem] min-w-0">{people}</div>
          <div className="border-t border-white/10 pt-2">
            <p
              className="h-5 truncate text-center text-sm font-semibold leading-5 text-white"
              aria-live="polite"
            >
              {status}
            </p>
          </div>
          <div className="h-9">{controls}</div>
        </>
      )}
    </div>
  );
}

/** A row that scrolls sideways inside the felt's people region, at its full height. */
export function FeltRow({
  label,
  children,
  className,
}: {
  label: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <ul
      aria-label={label}
      className={cn(
        "scrollbar-none flex h-full snap-x items-stretch gap-2 overflow-x-auto overflow-y-hidden overscroll-x-contain",
        className,
      )}
    >
      {children}
    </ul>
  );
}

/** A quiet message filling the people region (washed, watching, nobody yet). */
export function FeltNote({ children }: { children: ReactNode }) {
  return (
    <p className="flex h-full items-center justify-center rounded-lg bg-black/15 px-4 text-center text-xs text-white/70">
      {children}
    </p>
  );
}

const CARD_BACK =
  "border-white/40 bg-[repeating-linear-gradient(45deg,#b91c1c_0_3px,#7f1d1d_3px_6px)]";

/** The undealt deck on the felt. */
export function Deck({ label }: { label?: string }) {
  return (
    <span className="flex shrink-0 flex-col items-center gap-1">
      <span className="relative block h-14 w-10" aria-hidden>
        <span
          className={cn(
            "absolute inset-0 translate-x-1 translate-y-1 rounded-md border shadow",
            CARD_BACK,
          )}
        />
        <span className={cn("absolute inset-0 rounded-md border shadow-md", CARD_BACK)} />
      </span>
      {label && (
        <span className="font-mono text-[9px] font-bold uppercase tracking-wider text-white/60">
          {label}
        </span>
      )}
    </span>
  );
}

/** A card still face down where it will be turned over (the board before the flop, the dealer before the deal). */
export function FaceDown({ small }: { small?: boolean }) {
  return (
    <span
      className={cn(
        "shrink-0 rounded-md border shadow",
        CARD_BACK,
        small ? "h-10 w-7" : "h-14 w-10",
      )}
      aria-hidden
    />
  );
}

/**
 * A ring around a player's avatar that drains as their turn timer runs out (green, then red in the last quarter), so
 * whose clock is running shows at a glance in the people row.
 */
export function TimerRing({ fraction, children }: { fraction: number; children: ReactNode }) {
  const r = 20;
  const c = 2 * Math.PI * r;
  const left = Math.max(0, Math.min(1, fraction));
  return (
    <span className="relative inline-flex items-center justify-center p-1">
      <svg className="absolute inset-0 -rotate-90" viewBox="0 0 44 44" aria-hidden>
        <circle cx="22" cy="22" r={r} fill="none" strokeWidth="3" className="stroke-white/15" />
        <circle
          cx="22"
          cy="22"
          r={r}
          fill="none"
          strokeWidth="3"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - left)}
          className={cn(
            "transition-[stroke-dashoffset] duration-1000 ease-linear",
            left <= 0.25 ? "stroke-loss" : "stroke-primary",
          )}
        />
      </svg>
      {children}
    </span>
  );
}

/** Outline buttons on the green felt: see-through instead of the page background. */
export const FELT_OUTLINE =
  "border-white/25 bg-black/20 text-white hover:bg-black/35 hover:text-white";

/** What naming and drawing players needs: the viewer and the people cache. */
export interface People {
  user: User;
  expire: () => void;
  peopleKey: readonly string[];
}

/** A player's name, or "You". */
export function PlayerName({ id, user, expire, peopleKey }: People & { id: string }) {
  const person = usePerson(id, user, expire, peopleKey);
  return <>{id === user.id ? "You" : (person?.name ?? "…")}</>;
}

/** A tile in the people row: someone's avatar and name, and a line under it. */
export function Tile({
  id,
  line,
  tone,
  highlight,
  ...people
}: People & {
  id: string;
  line: ReactNode;
  tone?: "win" | "loss" | "muted" | "live";
  highlight?: boolean;
}) {
  return (
    <li
      className={cn(
        "flex w-20 shrink-0 snap-start flex-col items-center justify-center gap-1 rounded-lg border border-white/10 bg-black/20 p-1.5 text-center",
        highlight && "border-primary/70 bg-primary/10",
      )}
    >
      <PersonAvatar
        id={id}
        size="sm"
        user={people.user}
        expire={people.expire}
        queryKey={people.peopleKey}
      />
      <span className="w-full truncate text-[11px] font-semibold text-white">
        <PlayerName id={id} {...people} />
      </span>
      <span
        className={cn(
          "w-full truncate font-mono text-[10px] font-bold uppercase",
          tone === "win"
            ? "text-win"
            : tone === "loss"
              ? "text-loss"
              : tone === "live"
                ? "animate-pulse text-primary"
                : "text-white/70",
        )}
      >
        {line}
      </span>
    </li>
  );
}

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
 * can't, and what joining holds against your limit, are in the status line and Details). Anyone in can leave before
 * the deal, the proposer included.
 */
export function JoinControls({
  joined,
  full,
  disabled,
  onJoin,
  onLeave,
}: {
  joined: boolean;
  full: boolean;
  disabled: boolean;
  onJoin: () => void;
  onLeave: () => void;
}) {
  if (full && !joined) return null;
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

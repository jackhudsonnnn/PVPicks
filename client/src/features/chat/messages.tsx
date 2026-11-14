/** Chat message rows: text and GIF bubbles, bet and settlement cards, and reactions. */
import { useState } from "react";
import { ChevronRight, Scale, SmilePlus } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { StatusBadge, MODES, statusOf } from "@/features/bets";
import { useGifs } from "./gifs";
import { cn } from "@/lib/utils";
import { type User, PersonAvatar, usePerson, PersonName } from "@/features/people";
import { SafetyMenu, type SocialActions } from "@/features/social";
import { type ChatMessage } from "./api";
import { QUICK_REACTIONS, reactionChips } from "./model";
import { formatPoints } from "@/features/settlements";
import { timeLabel } from "@/lib/time";

interface RowProps {
  user: User;
  expire: () => void;
  names: readonly string[];
  tableId: string;
  onOpenBet?: ((betId: string) => void) | undefined;
  onSelectUser?: ((userId: string) => void) | undefined;
  social?: SocialActions | undefined;
  canReact: boolean;
  onReact: (emoji: string) => void;
}

export function MessageRow({ message, ...props }: RowProps & { message: ChatMessage }) {
  const time = timeLabel(message.createdAt);
  if (message.kind === "system") {
    return (
      <p className="py-1 text-center font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        {message.text} · {time}
      </p>
    );
  }
  if (message.kind === "settlement") return <SettlementCard message={message} {...props} />;
  if (message.kind === "bet") return <BetCard message={message} {...props} />;
  return <Bubble message={message} {...props} />;
}

function Bubble({
  message,
  user,
  expire,
  names,
  tableId,
  onSelectUser,
  social,
  canReact,
  onReact,
}: RowProps & { message: Extract<ChatMessage, { kind: "text" | "gif" }> }) {
  const mine = message.authorId === user.id;
  const person = usePerson(message.authorId, user, expire, names);
  const name = person?.name ?? "…";
  const gifs = useGifs();
  const gif =
    message.kind === "gif" ? gifs.data?.gifs.find((g) => g.id === message.gifId) : undefined;
  return (
    <div className={cn("flex items-end gap-2", mine && "flex-row-reverse")}>
      {!mine && (
        <button
          type="button"
          onClick={() => onSelectUser?.(message.authorId)}
          aria-label={`${name}'s profile`}
          disabled={!onSelectUser}
        >
          <PersonAvatar id={message.authorId} user={user} expire={expire} queryKey={names} />
        </button>
      )}
      <div className={cn("min-w-0 max-w-[78%]", mine && "text-right")}>
        {!mine && <p className="mb-0.5 text-[11px] text-muted-foreground">{name}</p>}
        <div className={cn("flex items-center gap-1", mine && "flex-row-reverse")}>
          {message.kind === "gif" ? (
            gif ? (
              <img
                src={gif.url}
                alt={gif.alt}
                loading="lazy"
                className="block w-44 max-w-full rounded-2xl bg-muted object-cover sm:w-52"
              />
            ) : (
              <p className="text-xs text-muted-foreground">GIF unavailable</p>
            )
          ) : (
            <p
              className={cn(
                "inline-block min-w-0 whitespace-pre-wrap break-words rounded-2xl px-3.5 py-2 text-left text-sm",
                mine
                  ? "rounded-br-md bg-primary text-primary-foreground"
                  : "rounded-bl-md bg-surface-raised text-foreground",
              )}
            >
              {message.text}
            </p>
          )}
          {canReact && <ReactButton message={message} user={user} onReact={onReact} />}
          {!mine && social && (
            <SafetyMenu
              userId={message.authorId}
              name={name}
              viewerId={user.id}
              actions={social}
              context={{
                tableId,
                messageId: message.id,
                messageText:
                  message.kind === "text" ? message.text : `GIF: ${gif?.alt ?? "unavailable"}`,
              }}
            />
          )}
        </div>
        <ReactionChips
          message={message}
          user={user}
          names={names}
          expire={expire}
          canReact={canReact}
          onReact={onReact}
          className={cn(mine && "justify-end")}
        />
        <p className="mt-0.5 font-mono text-[9px] text-muted-foreground">
          {timeLabel(message.createdAt)}
        </p>
      </div>
    </div>
  );
}

function BetCard({
  message,
  user,
  expire,
  names,
  onOpenBet,
  canReact,
  onReact,
}: RowProps & { message: Extract<ChatMessage, { kind: "bet" }> }) {
  const person = usePerson(message.authorId, user, expire, names);
  const summary = message.bet;
  const mode = MODES.find((m) => m.id === summary?.modeId) ?? MODES.find((m) => m.id === "u2pick")!;
  const Icon = mode.icon;
  return (
    <div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          disabled={!onOpenBet || !summary}
          onClick={() => onOpenBet?.(message.betId)}
          className="group flex min-w-0 flex-1 items-center gap-3 rounded-xl border bg-surface p-3 text-left transition-colors enabled:hover:border-primary/60"
        >
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-ticket text-ticket-foreground">
            <Icon className="size-4" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
              {person?.name ?? "…"} · {mode.name}
            </span>
            <span className="mt-0.5 line-clamp-2 break-words text-sm font-semibold leading-snug">
              {summary?.question ?? "A bet"}
            </span>
          </span>
          <span className="flex shrink-0 flex-col items-end gap-1">
            {summary && (
              <StatusBadge status={statusOf(summary.status)} className="px-1.5 py-0.5 text-[8px]" />
            )}
            {onOpenBet && summary && (
              <ChevronRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
            )}
          </span>
        </button>
        {canReact && <ReactButton message={message} user={user} onReact={onReact} />}
      </div>
      <ReactionChips
        message={message}
        user={user}
        names={names}
        expire={expire}
        canReact={canReact}
        onReact={onReact}
      />
    </div>
  );
}

/** The host's points settlement, posted for everyone: who pays whom. Payment tracking lives on Friends. */
function SettlementCard({
  message,
  user,
  expire,
  names,
}: RowProps & { message: Extract<ChatMessage, { kind: "settlement" }> }) {
  const lines = message.settlementSummary.lines;
  return (
    <div className="rounded-xl border bg-surface p-3">
      <p className="flex items-center gap-2 font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
        <Scale className="size-3.5" /> Points settled · {timeLabel(message.createdAt)}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        Settled by{" "}
        <PersonName
          id={message.settlementSummary.hostId}
          user={user}
          expire={expire}
          queryKey={names}
        />
        . {lines.length === 0 ? "Nobody owes anything." : "Settle up on the Friends page."}
      </p>
      {lines.length > 0 && (
        <ul className="mt-2 divide-y">
          {lines.map((line) => (
            <li
              key={`${line.payerId}-${line.payeeId}`}
              className="flex items-center justify-between gap-3 py-1.5 text-sm"
            >
              <span className="min-w-0 break-words">
                <PersonName id={line.payerId} user={user} expire={expire} queryKey={names} />
                <span className="text-muted-foreground"> pays </span>
                <PersonName id={line.payeeId} user={user} expire={expire} queryKey={names} />
              </span>
              <span className="scoreboard shrink-0 text-base">
                {formatPoints(line.amountCents)} points
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Small smiley that opens the quick-reaction bar. */
function ReactButton({
  message,
  user,
  onReact,
}: {
  message: ChatMessage;
  user: User;
  onReact: (emoji: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const current = reactionChips(message, user.id).find((chip) => chip.mine)?.emoji;
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Add a reaction"
          className="flex size-7 shrink-0 items-center justify-center rounded-full text-muted-foreground/60 transition-colors hover:bg-surface-raised hover:text-foreground data-[state=open]:bg-surface-raised data-[state=open]:text-foreground"
        >
          <SmilePlus className="size-4" />
        </button>
      </PopoverTrigger>
      <PopoverContent side="top" className="flex w-auto gap-0.5 rounded-full bg-sidebar p-1">
        {QUICK_REACTIONS.map((emoji) => (
          <button
            key={emoji}
            type="button"
            onClick={() => {
              setOpen(false);
              onReact(emoji);
            }}
            className={cn(
              "flex size-9 items-center justify-center rounded-full text-xl transition-transform hover:scale-125 hover:bg-surface-raised",
              current === emoji && "bg-primary/25 ring-1 ring-primary",
            )}
            aria-label={`React ${emoji}`}
            aria-pressed={current === emoji}
          >
            {emoji}
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}

/** Reaction chips under a message; tap one to add or remove your own. */
function ReactionChips({
  message,
  user,
  canReact,
  onReact,
  className,
}: {
  message: ChatMessage;
  user: User;
  names: readonly string[];
  expire: () => void;
  canReact: boolean;
  onReact: (emoji: string) => void;
  className?: string;
}) {
  const chips = reactionChips(message, user.id);
  if (!chips.length) return null;
  return (
    <div className={cn("mt-1 flex flex-wrap gap-1", className)}>
      {chips.map((chip) => (
        <button
          key={chip.emoji}
          type="button"
          disabled={!canReact}
          onClick={() => onReact(chip.emoji)}
          aria-pressed={chip.mine}
          aria-label={`${chip.emoji} ${chip.count}${chip.mine ? ", including you" : ""}`}
          className={cn(
            "flex animate-rise items-center gap-1 rounded-full border px-1.5 py-0.5 text-xs transition-colors disabled:cursor-default",
            chip.mine
              ? "border-primary/70 bg-primary/15 text-foreground"
              : "bg-surface text-muted-foreground enabled:hover:border-primary/40",
          )}
        >
          <span>{chip.emoji}</span>
          <span className="font-mono text-[10px] font-bold">{chip.count}</span>
        </button>
      ))}
    </div>
  );
}

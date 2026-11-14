import { useEffect, useLayoutEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ChevronRight,
  MessageSquareOff,
  Scale,
  Search,
  SendHorizontal,
  SmilePlus,
} from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { StatusBadge } from "@/features/bets/components/status-badge";
import { MODES } from "@/features/bets/modes";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { GIF_CATEGORIES, getGif, searchGifs, type GifCategory } from "@/features/table/gifs";
import { cn } from "@/lib/utils";
import {
  BackendError,
  backendErrorMessage,
  type BackendBet,
  type BackendTable,
  type BackendUser,
} from "./backend-api";
import { PersonAvatar } from "./backend-avatar";
import { SafetyMenu, type SocialActions } from "./backend-safety";
import { ticketStatus } from "./ticket-model";
import { usePerson } from "./use-person";
import { ConfirmAction } from "./backend-confirm-action";
import { PersonName } from "./backend-person-name";
import { markRead, type ChatMessage } from "./chat-api";
import { useChatActions } from "./chat-actions";
import {
  QUICK_REACTIONS,
  canReactTo,
  nextReaction,
  reactionChips,
  textProblem,
} from "./chat-model";
import { useRealtimeFeed, useRealtimeStatus } from "./realtime";
import { tableChatFeed } from "./realtime-connection";
import { formatPoints } from "./settlement-api";
import { useBackendChat } from "./use-backend-chat";
import { timeLabel } from "@/lib/time";

/** A table's chat: paged history, text and GIF sending, reactions and read position, updated live. */
export function BackendChat({
  user,
  table,
  expire,
  betQuestion,
  bet,
  onOpenBet,
  onSelectUser,
  social,
  onRead,
  readOnly = false,
  fill = false,
}: {
  user: BackendUser;
  table: Pick<BackendTable, "id" | "disabledPermissions">;
  expire: () => void;
  /** The question of a bet at this table, for bet cards; undefined while unknown. */
  betQuestion: (betId: string) => string | undefined;
  /** The full bet, when loaded, so bet cards show their mode and status. */
  bet?: (betId: string) => BackendBet | undefined;
  /** Opens a bet's ticket from its card. */
  onOpenBet?: (betId: string) => void;
  /** Opens someone's profile from their avatar. */
  onSelectUser?: (userId: string) => void;
  /** Report and Block on other people's messages; omitted in archives. */
  social?: SocialActions | undefined;
  /** Called after the read position moves, so unread counts can refresh. */
  onRead: () => void;
  /** A deleted table's archive: history only, no sending, reactions, read position or live updates. */
  readOnly?: boolean;
  /** Fill the parent's height (side panel) instead of capping the history at 60% of the screen. */
  fill?: boolean;
}) {
  const chat = useBackendChat(table.id, expire, { readOnly });
  const { state } = chat;
  useRealtimeFeed(readOnly ? null : tableChatFeed(table.id), () => void chat.refresh());
  // Changes may have been missed while disconnected, so refresh after every reconnect.
  const live = useRealtimeStatus() === "live";
  const { refresh } = chat;
  useEffect(() => {
    if (live) void refresh();
  }, [live, refresh]);
  const actions = useChatActions(
    user.id,
    async (intent) => {
      if (intent?.kind === "react") await chat.refreshAround(intent.messageId);
      else await chat.refresh();
    },
    expire,
  );
  const disabled = table.disabledPermissions[user.id] ?? [];
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const names = ["backend-tables", user.id, "people"] as const;

  useMarkRead(
    table.id,
    !readOnly && state.atEnd ? state.messages.at(-1)?.id : undefined,
    onRead,
    expire,
  );
  const scroller = useChatScroll(state.messages);

  if (state.status === "loading") return <p className="p-4">Loading chat…</p>;
  if (state.status === "error") {
    return (
      <div className="space-y-3 p-4">
        <p role="alert">{backendErrorMessage(state.error)}</p>
        <Button variant="outline" onClick={() => void chat.reload()}>
          Try again
        </Button>
      </div>
    );
  }

  return (
    <section
      className={cn("flex flex-col", fill ? "min-h-0 flex-1" : "rounded-2xl border bg-sidebar/60")}
      aria-label="Table chat"
    >
      <ChatActionStatus actions={actions} />
      <div className="relative flex min-h-0 flex-1 flex-col">
        <ScrollContainer
          ref={scroller}
          isVisible={false}
          className={cn(
            "space-y-3 px-4 py-4 [overflow-anchor:none]",
            fill ? "min-h-0 flex-1" : "max-h-[60vh]",
          )}
        >
          {state.olderCursor && (
            <div className="text-center">
              <Button
                variant="ghost"
                size="sm"
                disabled={state.busy}
                onClick={() => void chat.loadOlder()}
              >
                Load older messages
              </Button>
            </div>
          )}
          {state.messages.length === 0 ? (
            <EmptyState>No messages yet. Say hello.</EmptyState>
          ) : (
            <ol role="log" aria-live="polite" className="space-y-3">
              {state.messages.map((message) => (
                <li key={message.id}>
                  {message.id === state.firstUnreadId && (
                    <div className="mb-3 flex items-center gap-2">
                      <span className="h-px flex-1 bg-loss" />
                      <span className="font-mono text-[10px] font-bold uppercase tracking-widest text-loss">
                        New messages
                      </span>
                      <span className="h-px flex-1 bg-loss" />
                    </div>
                  )}
                  <MessageRow
                    message={message}
                    user={user}
                    expire={expire}
                    names={names}
                    tableId={table.id}
                    betQuestion={betQuestion}
                    bet={bet}
                    onOpenBet={onOpenBet}
                    onSelectUser={onSelectUser}
                    social={readOnly ? undefined : social}
                    canReact={
                      !readOnly && !disabled.includes("react") && !blocked && canReactTo(message)
                    }
                    onReact={(emoji) =>
                      actions.start({
                        kind: "react",
                        tableId: table.id,
                        messageId: message.id,
                        emoji: nextReaction(message, user.id, emoji),
                      })
                    }
                  />
                </li>
              ))}
            </ol>
          )}
        </ScrollContainer>
        {(!state.atEnd || state.newBelow) && (
          <Button
            type="button"
            size="sm"
            onClick={() => void chat.jumpToLatest()}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full shadow-lg"
          >
            <ArrowDown /> {state.newBelow ? "New messages · Jump to latest" : "Jump to latest"}
          </Button>
        )}
      </div>
      {!readOnly && (
        <Composer
          canText={!disabled.includes("text")}
          canGif={!disabled.includes("gifs")}
          blocked={blocked}
          send={(message) => actions.start({ kind: "send", tableId: table.id, message })}
        />
      )}
    </section>
  );
}

interface RowProps {
  user: BackendUser;
  expire: () => void;
  names: readonly string[];
  tableId: string;
  betQuestion: (betId: string) => string | undefined;
  bet?: ((betId: string) => BackendBet | undefined) | undefined;
  onOpenBet?: ((betId: string) => void) | undefined;
  onSelectUser?: ((userId: string) => void) | undefined;
  social?: SocialActions | undefined;
  canReact: boolean;
  onReact: (emoji: string) => void;
}

function MessageRow({ message, ...props }: RowProps & { message: ChatMessage }) {
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
  const gif = message.kind === "gif" ? getGif(message.gifId) : undefined;
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
  betQuestion,
  bet,
  onOpenBet,
  canReact,
  onReact,
}: RowProps & { message: Extract<ChatMessage, { kind: "bet" }> }) {
  const person = usePerson(message.authorId, user, expire, names);
  const full = bet?.(message.betId);
  const question = full?.question ?? betQuestion(message.betId);
  const mode = MODES.find((m) => m.id === (full?.modeId ?? "u2pick"))!;
  const Icon = mode.icon;
  return (
    <div>
      <div className="flex items-center gap-1">
        <button
          type="button"
          disabled={!onOpenBet || !full}
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
              {question ?? "A bet"}
            </span>
          </span>
          <span className="flex shrink-0 flex-col items-end gap-1">
            {full && (
              <StatusBadge status={ticketStatus(full)} className="px-1.5 py-0.5 text-[8px]" />
            )}
            {onOpenBet && full && (
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
  user: BackendUser;
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
  user: BackendUser;
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

function Composer({
  canText,
  canGif,
  blocked,
  send,
}: {
  canText: boolean;
  canGif: boolean;
  blocked: boolean;
  send: (message: { kind: "text"; text: string } | { kind: "gif"; gifId: string }) => void;
}) {
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = textProblem(text);
    setProblem(invalid);
    if (invalid) return;
    send({ kind: "text", text: text.trim() });
    setText("");
  }

  if (!canText && !canGif) {
    return (
      <p className="flex items-center justify-center gap-2 border-t bg-sidebar p-4 text-center text-xs text-muted-foreground">
        <MessageSquareOff className="size-4 shrink-0" /> A host turned off chat for you at this
        table.
      </p>
    );
  }
  return (
    <div className="border-t bg-sidebar p-3">
      {problem && (
        <p role="alert" className="mb-2 text-xs text-loss">
          {problem}
        </p>
      )}
      <form onSubmit={submit} className="flex items-center gap-2">
        {canGif && (
          <GifPicker disabled={blocked} onPick={(gifId) => send({ kind: "gif", gifId })} />
        )}
        <Input
          aria-label="Message"
          placeholder={canText ? "Talk trash…" : "A host turned off texts for you"}
          value={text}
          disabled={!canText || blocked}
          onChange={(event) => setText(event.target.value)}
          className="h-11 bg-surface"
        />
        <Button
          type="submit"
          size="icon"
          className="size-11 shrink-0"
          aria-label="Send"
          disabled={!canText || blocked}
        >
          <SendHorizontal />
        </Button>
      </form>
    </div>
  );
}

/** "GIF" button next to the input; picking one sends it straight away. */
function GifPicker({ disabled, onPick }: { disabled: boolean; onPick: (gifId: string) => void }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState<GifCategory>("hype");
  const results = searchGifs(search, category);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Choose a GIF"
          disabled={disabled}
          className="flex h-11 shrink-0 items-center rounded-md border bg-surface px-2.5 font-mono text-[11px] font-black tracking-wider text-muted-foreground transition-colors hover:border-primary/60 hover:text-foreground disabled:opacity-50"
        >
          GIF
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="top"
        align="start"
        className="w-[min(22rem,calc(100vw-1.5rem))] space-y-2 bg-sidebar p-2"
      >
        <div className="relative">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search GIFs"
            className="h-10 bg-surface pl-9"
            aria-label="Search GIFs"
          />
        </div>
        {!search.trim() && (
          <SegmentedTabs
            size="sm"
            ariaLabel="GIF category"
            tabs={GIF_CATEGORIES}
            value={category}
            onChange={setCategory}
            className="bg-surface p-1"
          />
        )}
        <ScrollContainer isVisible={false} className="grid h-64 grid-cols-2 content-start gap-1.5">
          {results.length === 0 && (
            <p className="col-span-2 py-10 text-center text-sm text-muted-foreground">
              No GIFs for “{search}”.
            </p>
          )}
          {results.map((gif) => (
            <button
              key={gif.id}
              type="button"
              aria-label={`Send GIF: ${gif.alt}`}
              onClick={() => {
                onPick(gif.id);
                setOpen(false);
                setSearch("");
              }}
              className="overflow-hidden rounded-lg bg-muted ring-primary transition hover:ring-2"
            >
              <img
                src={gif.url}
                alt=""
                loading="lazy"
                className="aspect-square w-full object-cover"
              />
            </button>
          ))}
        </ScrollContainer>
      </PopoverContent>
    </Popover>
  );
}

function ChatActionStatus({ actions }: { actions: ReturnType<typeof useChatActions> }) {
  return (
    <div className="space-y-2">
      {actions.storageError && <p role="alert">{actions.storageError} Sending is disabled.</p>}
      {actions.message && <p role="alert">{actions.message}</p>}
      {actions.pending && (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <p>
            {actions.pending.intent.kind === "send"
              ? "A message may not have been sent."
              : "A reaction may not have been saved."}{" "}
            Retry it before doing anything else.
          </p>
          <Button size="sm" disabled={actions.busy} onClick={actions.retry}>
            Retry
          </Button>{" "}
          <ConfirmAction
            label="Discard"
            title="Discard the saved action?"
            description="Check the chat first: it may already have gone through."
            disabled={actions.busy}
            onConfirm={actions.clearAfterReview}
          />
        </div>
      )}
    </div>
  );
}

/** Advances the read position to the newest message on screen, once per message. */
function useMarkRead(
  tableId: string,
  latestId: string | undefined,
  onRead: () => void,
  expire: () => void,
) {
  const marked = useRef<string | null>(null);
  const callbacks = useRef({ onRead, expire });
  callbacks.current = { onRead, expire };
  useEffect(() => {
    if (!latestId || marked.current === latestId) return;
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    marked.current = latestId;
    markRead(tableId, latestId, crypto.randomUUID())
      .then(() => callbacks.current.onRead())
      .catch((error: unknown) => {
        // The position only moves forward and the next message retries it; only a lost session matters.
        if (error instanceof BackendError && error.status === 401) callbacks.current.expire();
        else marked.current = null;
      });
  }, [tableId, latestId]);
}

/** Keeps the view pinned to the newest message and preserves position when older history loads. */
function useChatScroll(messages: ChatMessage[]) {
  const ref = useRef<HTMLDivElement>(null);
  const previous = useRef({
    first: undefined as string | undefined,
    last: undefined as string | undefined,
    height: 0,
    nearBottom: true,
  });
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const before = previous.current;
    const first = messages[0]?.id;
    const last = messages.at(-1)?.id;
    if (before.first !== undefined && first !== before.first && last === before.last) {
      element.scrollTop += element.scrollHeight - before.height;
    } else if (last !== before.last && before.nearBottom) {
      element.scrollTop = element.scrollHeight;
    }
    previous.current = {
      first,
      last,
      height: element.scrollHeight,
      nearBottom: element.scrollHeight - element.scrollTop - element.clientHeight < 80,
    };
  }, [messages]);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const track = () => {
      previous.current.nearBottom =
        element.scrollHeight - element.scrollTop - element.clientHeight < 80;
    };
    element.addEventListener("scroll", track);
    return () => element.removeEventListener("scroll", track);
  }, []);
  return ref;
}

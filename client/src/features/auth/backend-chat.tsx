import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { ArrowDown, ImageIcon, SendHorizontal, SmilePlus } from "lucide-react";
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
  type BackendTable,
  type BackendUser,
} from "./backend-api";
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
  onRead,
  readOnly = false,
}: {
  user: BackendUser;
  table: Pick<BackendTable, "id" | "disabledPermissions">;
  expire: () => void;
  /** The question of a bet at this table, for bet cards; undefined while unknown. */
  betQuestion: (betId: string) => string | undefined;
  /** Called after the read position moves, so unread counts can refresh. */
  onRead: () => void;
  /** A deleted table's archive: history only, no sending, reactions, read position or live updates. */
  readOnly?: boolean;
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
  const names = ["backend-tables", user.id, table.id, "chat"] as const;

  useMarkRead(
    table.id,
    !readOnly && state.atEnd ? state.messages.at(-1)?.id : undefined,
    onRead,
    expire,
  );
  const scroller = useChatScroll(state.messages);

  if (state.status === "loading") return <p>Loading chat…</p>;
  if (state.status === "error") {
    return (
      <div className="space-y-3">
        <p role="alert">{backendErrorMessage(state.error)}</p>
        <Button variant="outline" onClick={() => void chat.reload()}>
          Try again
        </Button>
      </div>
    );
  }

  return (
    <section className="space-y-3" aria-label="Table chat">
      <ChatActionStatus actions={actions} />
      <ScrollContainer ref={scroller} hasScrollbar className="max-h-[60vh] space-y-3">
        {state.olderCursor && (
          <div className="text-center">
            <Button
              variant="outline"
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
                  <p className="mb-2 border-t border-primary pt-1 text-center text-xs text-primary">
                    New
                  </p>
                )}
                <MessageRow
                  message={message}
                  user={user}
                  expire={expire}
                  names={names}
                  betQuestion={betQuestion}
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
        <Button size="sm" variant="outline" onClick={() => void chat.jumpToLatest()}>
          <ArrowDown /> {state.newBelow ? "New messages" : "Jump to latest"}
        </Button>
      )}
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

function MessageRow({
  message,
  user,
  expire,
  names,
  betQuestion,
  canReact,
  onReact,
}: {
  message: ChatMessage;
  user: BackendUser;
  expire: () => void;
  names: readonly string[];
  betQuestion: (betId: string) => string | undefined;
  canReact: boolean;
  onReact: (emoji: string) => void;
}) {
  const [picking, setPicking] = useState(false);
  const time = timeLabel(message.createdAt);
  if (message.kind === "system") {
    return (
      <p className="text-center text-xs text-muted-foreground">
        {message.text} · {time}
      </p>
    );
  }
  const chips = reactionChips(message, user.id);
  return (
    <div
      className={cn(
        "space-y-1 rounded-lg border bg-surface p-3",
        "authorId" in message && message.authorId === user.id && "border-primary/40",
      )}
    >
      <p className="text-xs text-muted-foreground">
        {"authorId" in message ? (
          <PersonName id={message.authorId} user={user} expire={expire} queryKey={names} />
        ) : (
          "Points settlement"
        )}{" "}
        · {time}
      </p>
      <MessageBody
        message={message}
        user={user}
        expire={expire}
        names={names}
        betQuestion={betQuestion}
      />
      {(chips.length > 0 || canReact) && (
        <div className="flex flex-wrap items-center gap-1">
          {chips.map((chip) => (
            <Button
              key={chip.emoji}
              size="sm"
              variant={chip.mine ? "secondary" : "ghost"}
              aria-pressed={chip.mine}
              aria-label={`${chip.emoji} ${chip.count}${chip.mine ? ", including you" : ""}`}
              disabled={!canReact}
              onClick={() => onReact(chip.emoji)}
            >
              {chip.emoji} {chip.count}
            </Button>
          ))}
          {canReact && (
            <Button
              size="sm"
              variant="ghost"
              aria-label="Add a reaction"
              aria-expanded={picking}
              onClick={() => setPicking(!picking)}
            >
              <SmilePlus />
            </Button>
          )}
          {picking &&
            QUICK_REACTIONS.map((emoji) => (
              <Button
                key={emoji}
                size="sm"
                variant="ghost"
                aria-label={`React ${emoji}`}
                onClick={() => {
                  setPicking(false);
                  onReact(emoji);
                }}
              >
                {emoji}
              </Button>
            ))}
        </div>
      )}
    </div>
  );
}

function MessageBody({
  message,
  user,
  expire,
  names,
  betQuestion,
}: {
  message: Exclude<ChatMessage, { kind: "system" }>;
  user: BackendUser;
  expire: () => void;
  names: readonly string[];
  betQuestion: (betId: string) => string | undefined;
}) {
  switch (message.kind) {
    case "text":
      return <p className="whitespace-pre-wrap break-words">{message.text}</p>;
    case "gif": {
      const gif = getGif(message.gifId);
      return gif ? (
        <img src={gif.url} alt={gif.alt} className="max-h-48 rounded-md" loading="lazy" />
      ) : (
        <p className="text-muted-foreground">GIF unavailable</p>
      );
    }
    case "bet":
      return <p>Proposed a bet: {betQuestion(message.betId) ?? "see the Bets tab"}</p>;
    case "settlement":
      return (
        <ul className="text-sm">
          {message.settlementSummary.lines.map((line) => (
            <li key={`${line.payerId}-${line.payeeId}`}>
              <PersonName id={line.payerId} user={user} expire={expire} queryKey={names} /> owes{" "}
              <PersonName id={line.payeeId} user={user} expire={expire} queryKey={names} />{" "}
              {formatPoints(line.amountCents)} points
            </li>
          ))}
        </ul>
      );
  }
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
  const [gifs, setGifs] = useState(false);
  const [category, setCategory] = useState<GifCategory>("hype");
  const [search, setSearch] = useState("");

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const invalid = textProblem(text);
    setProblem(invalid);
    if (invalid) return;
    send({ kind: "text", text: text.trim() });
    setText("");
  }

  if (!canText && !canGif) return <p>You do not have permission to send messages at this table.</p>;
  return (
    <div className="space-y-2">
      <form onSubmit={submit} className="flex gap-2">
        <Input
          aria-label="Message"
          placeholder={canText ? "Message the table" : "Text messages are turned off for you"}
          value={text}
          disabled={!canText || blocked}
          onChange={(event) => setText(event.target.value)}
        />
        {canGif && (
          <Button
            type="button"
            variant="outline"
            aria-label="Choose a GIF"
            aria-expanded={gifs}
            disabled={blocked}
            onClick={() => setGifs(!gifs)}
          >
            <ImageIcon />
          </Button>
        )}
        <Button type="submit" aria-label="Send" disabled={!canText || blocked}>
          <SendHorizontal />
        </Button>
      </form>
      {problem && <p role="alert">{problem}</p>}
      {gifs && (
        <div className="space-y-2 rounded-lg border bg-surface p-3">
          <SegmentedTabs
            size="sm"
            ariaLabel="GIF category"
            tabs={GIF_CATEGORIES}
            value={category}
            onChange={setCategory}
          />
          <Input
            aria-label="Search GIFs"
            placeholder="Search GIFs"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <ScrollContainer className="grid max-h-60 grid-cols-3 gap-2">
            {searchGifs(search, category).map((gif) => (
              <button
                key={gif.id}
                type="button"
                className="overflow-hidden rounded-md"
                aria-label={`Send GIF: ${gif.alt}`}
                disabled={blocked}
                onClick={() => {
                  setGifs(false);
                  send({ kind: "gif", gifId: gif.id });
                }}
              >
                <img src={gif.url} alt="" className="h-20 w-full object-cover" loading="lazy" />
              </button>
            ))}
          </ScrollContainer>
        </div>
      )}
    </div>
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

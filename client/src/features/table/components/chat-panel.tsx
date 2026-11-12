import { useCallback, useEffect, useMemo, useState } from "react";
import { ArrowDown, ChevronRight, MessageSquareOff, SendHorizontal } from "lucide-react";
import { hasPermission } from "../hosts";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { myBlocks, useAppStore, useMe, useGetUser } from "@/features/store";
import { getMode } from "@/features/bets/modes";
import { StatusBadge } from "@/features/bets/components/status-badge";
import type { Bet } from "@/features/bets/types";
import type { Message } from "@/features/table/types";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { ScrollContainer } from "@/components/scroll-container";
import { getGif } from "../gifs";
import { GifPicker, ReactButton, ReactionChips } from "./chat-extras";
import { SettlementCard } from "./settlement-card";
import { advanceRead, unreadCount } from "../unread";
import { useChatWindow } from "../use-chat-window";
import { useAuth } from "@/features/auth/auth-provider";
import { accountStorageKeys } from "@/features/auth/storage-keys";
import { isRecord, readJson, writeJson } from "@/lib/storage";
import { SafetyMenu } from "@/features/social/components/safety-dialogs";

/** Last-read message id per table, saved in this browser per account. */
function useLastRead(tableId: string) {
  const { account } = useAuth();
  const key = accountStorageKeys(account ?? "none").lastRead;
  const [map, setMap] = useState<Record<string, string> | null>(null);
  useEffect(() => {
    setMap(readJson(key, {}, isRecord));
  }, [key]);
  const save = useCallback(
    (id: string | undefined) => {
      if (!map || !id || map[tableId] === id) return;
      const next = { ...map, [tableId]: id };
      writeJson(key, next);
      setMap(next);
    },
    [key, tableId, map],
  );
  return { loaded: map !== null, lastRead: map?.[tableId], save };
}

export function ChatPanel(props: {
  tableId: string;
  onBetClick: (bet: Bet) => void;
  onSelectUser: (id: string) => void;
  className?: string;
}) {
  return <TableChat key={props.tableId} {...props} />;
}

function TableChat({
  tableId,
  onBetClick,
  onSelectUser,
  className,
}: {
  tableId: string;
  onBetClick: (bet: Bet) => void;
  onSelectUser: (id: string) => void;
  className?: string;
}) {
  const me = useMe();
  const { state, actions } = useAppStore();
  const messages = useMemo(
    () => state.messages.filter((m) => m.tableId === tableId),
    [state.messages, tableId],
  );
  const betsById = useMemo(() => new Map(state.bets.map((bet) => [bet.id, bet])), [state.bets]);
  const table = state.tables.find((t) => t.id === tableId);
  const canText = !!table && hasPermission(table, me, "text");
  const canGif = !!table && hasPermission(table, me, "gifs");
  const [text, setText] = useState("");
  const { loaded, lastRead, save } = useLastRead(tableId);
  const saveRead = useCallback(
    (id: string | undefined) => {
      if (id) save(advanceRead(messages, lastRead, id));
    },
    [messages, lastRead, save],
  );
  const { scroller, window, dividerId, atLatest, onScroll, jumpToLatest } = useChatWindow(
    messages,
    loaded,
    lastRead,
    me,
    saveRead,
  );
  const visible = window ? messages.slice(window.start, window.end) : [];

  const unread = unreadCount(messages, lastRead, me);

  function send(e: React.FormEvent) {
    e.preventDefault();
    if (!text.trim() || !canText) return;
    actions.send(tableId, text.trim());
    setText("");
    jumpToLatest();
  }

  return (
    <div className={cn("flex min-h-0 flex-col", className)}>
      <div className="relative flex min-h-0 flex-1 flex-col">
        <ScrollContainer
          ref={scroller}
          isVisible={false}
          onScroll={onScroll}
          className="flex-1 space-y-3 px-4 py-4 [overflow-anchor:none]"
        >
          {!!window?.start && (
            <p className="py-1 text-center text-xs text-muted-foreground">
              {window.start.toLocaleString()} earlier messages
            </p>
          )}
          {/* History windows replace rows instantly: exit animations would retain pruned DOM. */}
          <div className="space-y-3">
            {visible.map((m) => (
              <div key={m.id} data-chat-message={m.id}>
                {m.id === dividerId && (
                  <div data-unread-line className="mb-3 flex scroll-mt-2 items-center gap-2">
                    <span className="h-px flex-1 bg-loss" />
                    <span className="font-mono text-[10px] font-bold uppercase tracking-widest text-loss">
                      New messages
                    </span>
                    <span className="h-px flex-1 bg-loss" />
                  </div>
                )}
                <Row
                  m={m}
                  bet={m.kind === "bet" ? betsById.get(m.betId) : undefined}
                  onBetClick={onBetClick}
                  onSelectUser={onSelectUser}
                />
              </div>
            ))}
          </div>
          {window && window.end < messages.length && (
            <p className="py-1 text-center text-xs text-muted-foreground">
              {(messages.length - window.end).toLocaleString()} later messages
            </p>
          )}
        </ScrollContainer>
        {/* Only offer the jump once a couple of pages (50 messages) are out of view. */}
        {window && !atLatest && messages.length - window.end >= 50 && (
          <Button
            type="button"
            size="sm"
            onClick={jumpToLatest}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full shadow-lg"
          >
            <ArrowDown /> {unread > 0 ? `${unread} new · Jump to latest` : "Jump to latest"}
          </Button>
        )}
      </div>
      {!canText && !canGif ? (
        <p className="flex items-center justify-center gap-2 border-t bg-sidebar p-4 text-center text-xs text-muted-foreground">
          <MessageSquareOff className="size-4 shrink-0" /> A host turned off chat for you at this
          table.
        </p>
      ) : (
        <form onSubmit={send} className="flex items-center gap-2 border-t bg-sidebar p-3">
          {canGif && <GifPicker onPick={(id) => actions.sendGif(tableId, id)} />}
          <Input
            value={text}
            onChange={(e) => setText(e.target.value)}
            disabled={!canText}
            placeholder={canText ? "Talk trash…" : "A host turned off texts for you"}
            className="h-11 bg-surface"
            aria-label="Message"
          />
          <Button
            type="submit"
            size="icon"
            className="size-11 shrink-0"
            aria-label="Send"
            disabled={!canText}
          >
            <SendHorizontal />
          </Button>
        </form>
      )}
    </div>
  );
}

function Row({
  m,
  bet,
  onBetClick,
  onSelectUser,
}: {
  m: Message;
  bet: Bet | undefined;
  onBetClick: (bet: Bet) => void;
  onSelectUser: (id: string) => void;
}) {
  if (m.kind === "system") {
    return (
      <p className="py-1 text-center font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        {m.text} · {m.at}
      </p>
    );
  }
  if (m.kind === "settlement") return <SettlementCard m={m} />;
  return <AuthoredRow m={m} bet={bet} onBetClick={onBetClick} onSelectUser={onSelectUser} />;
}

function AuthoredRow({
  m,
  bet,
  onBetClick,
  onSelectUser,
}: {
  m: Extract<Message, { kind: "text" | "gif" | "bet" }>;
  bet: Bet | undefined;
  onBetClick: (bet: Bet) => void;
  onSelectUser: (id: string) => void;
}) {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const [reveal, setReveal] = useState(false);
  const blocked = myBlocks(state).includes(m.authorId);
  // Blocked people's chats are hidden; tickets stay visible since they affect the table's points.
  if (blocked && m.kind !== "bet" && !reveal) {
    return (
      <p className="flex items-center justify-center gap-2 py-1 text-center text-[11px] text-muted-foreground">
        Message from someone you blocked
        <button type="button" onClick={() => setReveal(true)} className="font-semibold underline">
          Show
        </button>
      </p>
    );
  }
  const mine = m.authorId === me;
  const first = mine ? "You" : getUser(m.authorId).username.split(" ")[0];

  if (m.kind === "bet") {
    if (!bet) return null;
    const mode = getMode(bet.modeId);
    const Icon = mode.icon;
    return (
      <div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => onBetClick(bet)}
            className="group flex min-w-0 flex-1 items-center gap-3 rounded-xl border bg-surface p-3 text-left transition-colors hover:border-primary/60"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-ticket text-ticket-foreground">
              <Icon className="size-4" />
            </span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                {first} · {mode.name}
              </span>
              <span className="mt-0.5 line-clamp-2 text-sm font-semibold leading-snug">
                {bet.question}
              </span>
            </span>
            <span className="flex shrink-0 flex-col items-end gap-1">
              <StatusBadge status={bet.status} className="px-1.5 py-0.5 text-[8px]" />
              <ChevronRight className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
            </span>
          </button>
          <ReactButton messageId={m.id} />
        </div>
        <ReactionChips messageId={m.id} />
      </div>
    );
  }

  return (
    <div className={cn("flex items-end gap-2", mine && "flex-row-reverse")}>
      {!mine && (
        <button onClick={() => onSelectUser(m.authorId)} aria-label={`${first}'s profile`}>
          <UserAvatar userId={m.authorId} size="sm" />
        </button>
      )}
      <div className={cn("max-w-[78%]", mine && "text-right")}>
        {!mine && <p className="mb-0.5 text-[11px] text-muted-foreground">{first}</p>}
        <div className={cn("flex items-center gap-1", mine && "flex-row-reverse")}>
          {m.kind === "gif" ? (
            <GifBubble gifId={m.gifId} />
          ) : (
            <p
              className={cn(
                "inline-block min-w-0 rounded-2xl px-3.5 py-2 text-left text-sm",
                mine
                  ? "rounded-br-md bg-primary text-primary-foreground"
                  : "rounded-bl-md bg-surface-raised text-foreground",
              )}
            >
              {m.text}
            </p>
          )}
          <ReactButton messageId={m.id} />
          {!mine && (
            <SafetyMenu
              userId={m.authorId}
              context={{
                tableId: m.tableId,
                messageId: m.id,
                messageText:
                  m.kind === "text" ? m.text : `GIF: ${getGif(m.gifId)?.alt ?? "unavailable"}`,
              }}
            />
          )}
        </div>
        <ReactionChips messageId={m.id} className={cn(mine && "justify-end")} />
        <p className="mt-0.5 font-mono text-[9px] text-muted-foreground">{m.at}</p>
      </div>
    </div>
  );
}

function GifBubble({ gifId }: { gifId: string }) {
  const gif = getGif(gifId);
  if (!gif) return <p className="text-xs text-muted-foreground">GIF unavailable</p>;
  return (
    <img
      src={gif.url}
      alt={gif.alt}
      loading="lazy"
      className="block w-44 max-w-full rounded-2xl bg-muted object-cover sm:w-52"
    />
  );
}

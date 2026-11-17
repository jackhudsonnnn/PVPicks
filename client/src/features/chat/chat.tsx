import { useEffect, useLayoutEffect, useRef } from "react";
import { ArrowDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { cn } from "@/lib/utils";
import { backendErrorMessage } from "@/lib/api-client";
import type { Table } from "@/features/tables";
import { type User } from "@/features/people";
import { type SocialActions } from "@/features/social";
import { ActionStatus } from "@/components/action-status";
import { userKey } from "@/lib/query-keys";
import { type ChatMessage } from "./api";
import { useMarkRead } from "./use-mark-read";
import { useChatActions } from "./actions";
import { canReactTo, nextReaction, nextVote } from "./model";
import { useRealtimeFeed, useRealtimeStatus } from "@/lib/realtime";
import { tableChatFeed } from "@/lib/realtime-connection";
import { useBackendChat } from "./use-chat";
import { MessageRow } from "./messages";
import { Composer } from "./composer";
import { LoadingChat } from "@/components/loading";

/** A table's chat: paged history, text, GIFs and polls, reactions, votes and read position, updated live. */
export function TableChat({
  user,
  table,
  expire,
  onOpenBet,
  onSelectUser,
  social,
  readOnly = false,
  fill = false,
}: {
  user: User;
  table: Pick<Table, "id" | "disabledPermissions" | "pollsEnabled">;
  expire: () => void;
  /** Opens a bet's ticket from its card. */
  onOpenBet?: (betId: string) => void;
  /** Opens someone's profile from their avatar. */
  onSelectUser?: (userId: string) => void;
  /** Report and Block on other people's messages; omitted in archives. */
  social?: SocialActions | undefined;
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
      if (intent?.kind === "react" || intent?.kind === "vote")
        await chat.refreshAround(intent.messageId);
      else await chat.refresh();
    },
    expire,
  );
  const disabled = table.disabledPermissions[user.id] ?? [];
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const names = userKey(user.id, "people");

  useMarkRead(table.id, !readOnly && state.atEnd ? state.messages.at(-1)?.id : undefined, expire);
  const scroller = useChatScroll(state.messages);

  if (state.status === "loading") return <LoadingChat />;
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
      <ActionStatus
        actions={actions}
        pendingText={
          actions.pending?.intent.kind === "send"
            ? "A message may not have been sent."
            : actions.pending?.intent.kind === "poll"
              ? "A poll may not have been posted."
              : actions.pending?.intent.kind === "vote"
                ? "A vote may not have been saved."
                : "A reaction may not have been saved."
        }
        checkText="Check the chat first."
      />
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
                    onOpenBet={onOpenBet}
                    onSelectUser={onSelectUser}
                    social={readOnly ? undefined : social}
                    canReact={
                      !readOnly && !disabled.includes("react") && !blocked && canReactTo(message)
                    }
                    canVote={!readOnly && !blocked}
                    onVote={(option) =>
                      message.kind === "poll" &&
                      actions.start({
                        kind: "vote",
                        tableId: table.id,
                        messageId: message.id,
                        option: nextVote(message, user.id, option),
                      })
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
          canPoll={table.pollsEnabled && !disabled.includes("polls")}
          blocked={blocked}
          send={(message) => actions.start({ kind: "send", tableId: table.id, message })}
          startPoll={(poll) => actions.start({ kind: "poll", tableId: table.id, ...poll })}
        />
      )}
    </section>
  );
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

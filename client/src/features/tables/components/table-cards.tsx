import { type ReactNode, type RefObject, useLayoutEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ChevronRight, Crown, Mail } from "lucide-react";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ActionStatus } from "@/components/action-status";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { backendErrorMessage } from "@/lib/api-client";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed } from "@/lib/realtime-connection";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { cn } from "@/lib/utils";
import { useInPlayTables } from "@/features/bets";
import { previewText, useChatPreview } from "@/features/chat";
import { AvatarStack, type User, usePerson } from "@/features/people";
import {
  type InviteActions,
  type TableInvite,
  useInviteActions,
  useInvites,
} from "@/features/social";
import { listTables, type TableSummary } from "@/features/tables/api";
import { PageEdge } from "@/components/client-pages";
import { useClientPages } from "@/components/use-client-pages";
import { NAV_SIDEBAR, useMediaQuery } from "@/lib/use-media-query";
import { byRecentBet, newestFirst } from "@/features/tables/table-order";
import { CreateTableDialog } from "./create-table-dialog";
import { InviteDialog } from "./invite-dialog";
import { LoadingList } from "@/components/loading";

type Props = { user: User; expire: () => void };

/** Below Tailwind's `lg` the cards sit in one sideways row; from it they stack in a box. */
const STACKED = "(min-width: 1024px)";

/**
 * Your tables as cards, by most recent bet (up to 100; they mount 20 at a time as you scroll). A table where you
 * have a live pick shows green.
 *
 * - Phones and tablets: one sideways row that snaps a card to the center. It opens centered on your most recent
 *   table; without the desktop sidebar, table requests sit to its left, newest nearest, older further left.
 * - Desktop: the cards stack in a scrolling box, the same height as Home's Tickets box; without the sidebar,
 *   requests come first.
 * - With the sidebar, requests live there instead (see `SidebarTables`).
 */
export function TableCards({ user, expire }: Props) {
  const sidebar = useMediaQuery(NAV_SIDEBAR);
  return sidebar ? (
    <TableCardList user={user} expire={expire} />
  ) : (
    <TableCardsWithRequests user={user} expire={expire} />
  );
}

/** Mounted only without the sidebar, so one invite-actions hook is live at a time (the sidebar has the other). */
function TableCardsWithRequests({ user, expire }: Props) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const actions = useInviteActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
    (tableId) => void navigate({ to: "/tables/$tableId", params: { tableId } }),
  );
  return <TableCardList user={user} expire={expire} actions={actions} />;
}

function TableCardList({ user, expire, actions }: Props & { actions?: InviteActions }) {
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const invites = useInvites(user.id);
  useExpireOnUnauthorized([tables.error, invites.error], expire);
  const requests = newestFirst(invites.data ?? []);
  const inPlay = useInPlayTables(user.id);
  const tablePages = useClientPages(byRecentBet(tables.data ?? []));
  const requestPages = useClientPages(actions ? requests : []);
  const stacked = useMediaQuery(STACKED);
  const scroller = useRef<HTMLDivElement>(null);
  const requestGroup = useRef<HTMLDivElement>(null);
  const firstTable = useRef<HTMLDivElement>(null);
  useKeepTablesInView({ scroller, requestGroup, firstTable, sideways: !stacked });

  return (
    <section className="space-y-3" aria-labelledby="tables-heading">
      <div className="flex items-center justify-between gap-3">
        <h2
          id="tables-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Tables
        </h2>
        <CreateTableDialog user={user} expire={expire} />
      </div>
      {actions && (
        <ActionStatus
          actions={actions}
          pendingText="A request response may not have been saved."
          checkText="Check your tables and requests first."
        />
      )}
      {actions && invites.isError && <p role="alert">{backendErrorMessage(invites.error)}</p>}
      {tables.isPending ? (
        <LoadingList label="Loading tables" rows={2} rowClassName="h-20" />
      ) : tables.isError ? (
        <p role="alert">{backendErrorMessage(tables.error)}</p>
      ) : tables.data.length === 0 && requestPages.shown.length === 0 ? (
        <EmptyState boxed className="p-6">
          {requests.length > 0
            ? "No tables yet. Answer a request in the sidebar, or create a table and invite your friends."
            : "No tables yet. Create one and invite your friends."}
        </EmptyState>
      ) : (
        <ScrollContainer
          ref={scroller}
          isHorizontal="mobile"
          isVisible={false}
          // Centers the first table and offsets requests itself (useKeepTablesInView).
          keepPosition={false}
          aria-label={requestPages.shown.length ? "Your tables and requests" : "Your tables"}
          className="snap-x snap-mandatory gap-3 py-1 md:scrollbar-thin lg:h-[27rem] lg:snap-none lg:gap-2 lg:rounded-2xl lg:border lg:p-2"
        >
          {actions && requestPages.shown.length > 0 && (
            // Newest first in the DOM; sideways it's reversed, so the newest sits next to your tables and older
            // ones (and the edge that loads more) run off to the left.
            <div
              ref={requestGroup}
              className="flex shrink-0 flex-row-reverse gap-3 lg:flex-col lg:gap-2"
              aria-label="Requests"
              role="group"
            >
              <AnimatedList>
                {requestPages.shown.map((invite) => (
                  <AnimatedItem key={`invite-${invite.id}`} isHorizontal className={CARD_SLOT}>
                    <InviteDialog
                      invite={invite}
                      actions={actions}
                      user={user}
                      expire={expire}
                      triggerClassName={cn(
                        CARD,
                        "border-invite/70 bg-invite/10 transition-colors hover:bg-invite/15",
                      )}
                    >
                      <InviteCard invite={invite} user={user} expire={expire} />
                    </InviteDialog>
                  </AnimatedItem>
                ))}
              </AnimatedList>
              {requestPages.hasMore && (
                <PageEdge
                  onMore={requestPages.more}
                  label="Older requests"
                  className="self-center lg:self-stretch"
                />
              )}
            </div>
          )}
          <div
            className="flex shrink-0 gap-3 lg:flex-col lg:gap-2"
            role="group"
            aria-label="Tables"
          >
            <AnimatedList>
              {tablePages.shown.map((table, index) => (
                <AnimatedItem
                  key={`table-${table.id}`}
                  ref={index === 0 ? firstTable : undefined}
                  isHorizontal
                  className={CARD_SLOT}
                >
                  <TableCard
                    table={table}
                    inPlay={inPlay.has(table.id)}
                    user={user}
                    expire={expire}
                  />
                </AnimatedItem>
              ))}
            </AnimatedList>
            {tablePages.hasMore && (
              <PageEdge
                onMore={tablePages.more}
                label="More tables"
                className="self-center lg:self-stretch"
              />
            )}
          </div>
        </ScrollContainer>
      )}
    </section>
  );
}

/**
 * Sideways only: opens with your most recent table centered (requests wait to its left), and keeps whatever you're
 * looking at in place when requests are added or answered on the left, instead of letting the row jump.
 */
function useKeepTablesInView({
  scroller,
  requestGroup,
  firstTable,
  sideways,
}: {
  scroller: RefObject<HTMLDivElement | null>;
  requestGroup: RefObject<HTMLDivElement | null>;
  firstTable: RefObject<HTMLDivElement | null>;
  sideways: boolean;
}) {
  const centered = useRef(false);
  const requestWidth = useRef(0);
  useLayoutEffect(() => {
    const row = scroller.current;
    const first = firstTable.current;
    const width = requestGroup.current?.offsetWidth ?? 0;
    if (!sideways || !row) {
      centered.current = false;
      requestWidth.current = width;
      return;
    }
    if (!centered.current && first) {
      const card = first.getBoundingClientRect();
      const box = row.getBoundingClientRect();
      row.scrollLeft += card.left + card.width / 2 - (box.left + box.width / 2);
      centered.current = true;
    } else if (width !== requestWidth.current) {
      row.scrollLeft += width - requestWidth.current;
    }
    requestWidth.current = width;
  });
}

const CARD_SLOT = "w-72 max-w-[85%] shrink-0 snap-center lg:w-full lg:max-w-none";
const CARD =
  "group flex h-56 w-full min-w-0 flex-col gap-3 rounded-xl border p-4 text-left lg:h-auto lg:flex-row lg:items-center";

function CardBody({
  emoji,
  emojiClass,
  name,
  badge,
  line,
  memberPreview,
  memberCount,
  chevronClass,
  user,
  expire,
}: Props & {
  emoji: string;
  emojiClass: string;
  name: string;
  badge: ReactNode;
  line: string;
  memberPreview: string[];
  memberCount: number;
  chevronClass: string;
}) {
  return (
    <>
      <span
        className={cn(
          "flex size-12 shrink-0 items-center justify-center rounded-xl text-2xl",
          emojiClass,
        )}
      >
        {emoji}
      </span>
      <span className="block min-w-0 flex-1">
        <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
          <span className="break-words font-display text-xl font-bold uppercase leading-none">
            {name}
          </span>
          {badge}
        </span>
        <span className="mt-1.5 line-clamp-2 block break-words text-sm leading-snug text-muted-foreground">
          {line}
        </span>
      </span>
      <span className="flex shrink-0 items-center justify-between gap-3">
        <AvatarStack
          ids={memberPreview}
          total={memberCount}
          max={3}
          user={user}
          expire={expire}
          queryKey={userKey(user.id, "people")}
        />
        <ChevronRight
          className={cn(
            "size-4 shrink-0 transition-transform group-hover:translate-x-0.5",
            chevronClass,
          )}
        />
      </span>
    </>
  );
}

function TableCard({
  table,
  inPlay,
  user,
  expire,
}: Props & { table: TableSummary; inPlay: boolean }) {
  const queryClient = useQueryClient();
  // Bets here changed: your in-play state (and Home's tickets) may have too.
  useRealtimeFeed(tableBetsFeed(table.id), () => {
    void queryClient.invalidateQueries({ queryKey: userKey(user.id, "my-bets") });
  });
  const preview = useChatPreview(user.id, table.id);
  const message = preview?.message ?? null;
  const author = usePerson(message?.authorId ?? user.id, user, expire, userKey(user.id, "people"));
  const line = preview
    ? previewText(message, message?.authorId ? (author?.name ?? null) : null)
    : "";
  return (
    <Link
      to="/tables/$tableId"
      params={{ tableId: table.id }}
      className={cn(
        CARD,
        "transition-colors hover:border-primary/60",
        inPlay
          ? "border-primary/60 bg-primary/10 hover:bg-primary/15"
          : "bg-surface hover:bg-surface-raised",
      )}
    >
      <CardBody
        emoji={table.emoji}
        emojiClass={inPlay ? "bg-primary/20" : "bg-muted"}
        name={table.name}
        badge={
          <>
            {table.host && (
              <span className="flex shrink-0 items-center gap-1 rounded-full border border-primary/60 px-1.5 font-mono text-[10px] font-bold uppercase text-primary">
                <Crown className="size-3" /> Host
              </span>
            )}
            {inPlay && <span className="sr-only">In play</span>}
          </>
        }
        line={line}
        memberPreview={table.memberPreview}
        memberCount={table.memberCount}
        chevronClass="text-muted-foreground"
        user={user}
        expire={expire}
      />
    </Link>
  );
}

function InviteCard({ invite, user, expire }: Props & { invite: TableInvite }) {
  const from = usePerson(invite.fromId, user, expire, userKey(user.id, "people"));
  return (
    <CardBody
      emoji={invite.tableEmoji}
      emojiClass="bg-invite/20"
      name={invite.tableName}
      badge={
        <span className="flex shrink-0 items-center gap-1 rounded-full bg-invite px-1.5 font-mono text-[10px] font-bold uppercase text-invite-foreground">
          <Mail className="size-3" /> Request
        </span>
      }
      line={`${from?.name ?? "Someone"} wants you at this table. Accept?`}
      memberPreview={invite.memberPreview}
      memberCount={invite.memberCount}
      chevronClass="text-invite"
      user={user}
      expire={expire}
    />
  );
}

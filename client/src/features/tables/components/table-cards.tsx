import type { ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronRight, Crown, Mail } from "lucide-react";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { ActionStatus } from "@/components/action-status";
import { EmptyState } from "@/components/empty-state";
import { ScrollContainer } from "@/components/scroll-container";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
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
  type SocialActions,
  type TableInvite,
  useInvites,
  useSocialActions,
} from "@/features/social";
import { listTables, type Table } from "@/features/tables/api";
import { CreateTableDialog } from "./create-table-dialog";

type Props = { user: User; expire: () => void };

/**
 * Your invitations and tables as cards. On phones they sit in one row that scrolls sideways, snapping card by card;
 * on desktop they stack as rows in a scrolling box, the same height as Home's Tickets box. Invitations come first, and opening one asks to accept or
 * decline. A table where you have a live pick shows green.
 */
export function TableCards({ user, expire }: Props) {
  const queryClient = useQueryClient();
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const invites = useInvites(user.id);
  useExpireOnUnauthorized([tables.error, invites.error], expire);
  // One action hook for every invitation card: two instances with one storage key don't sync.
  const actions = useSocialActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const pending = invites.data ?? [];
  const inPlay = useInPlayTables(user.id);

  return (
    <section className="space-y-3" aria-labelledby="tables-heading">
      <div className="flex items-center justify-between gap-3">
        <h2
          id="tables-heading"
          className="font-mono text-xs font-bold uppercase tracking-[0.2em] text-muted-foreground"
        >
          Your tables
        </h2>
        <CreateTableDialog user={user} expire={expire} />
      </div>
      <ActionStatus
        actions={actions}
        pendingText="An invitation response may not have been saved."
        checkText="Check your tables and invitations first."
      />
      {invites.isError && <p role="alert">{backendErrorMessage(invites.error)}</p>}
      {tables.isPending ? (
        <p className="text-sm text-muted-foreground">Loading tables…</p>
      ) : tables.isError ? (
        <p role="alert">{backendErrorMessage(tables.error)}</p>
      ) : tables.data.length === 0 && pending.length === 0 ? (
        <EmptyState boxed className="p-6">
          No tables yet. Create one and invite your friends.
        </EmptyState>
      ) : (
        <ScrollContainer
          isHorizontal="mobile"
          isVisible={false}
          aria-label="Your tables and invitations"
          className="snap-x snap-mandatory gap-3 py-1 md:scrollbar-thin lg:h-[27rem] lg:snap-none lg:gap-2 lg:rounded-2xl lg:border lg:p-2"
        >
          <AnimatedList>
            {pending.map((invite) => (
              <AnimatedItem key={`invite-${invite.id}`} isHorizontal className={CARD_SLOT}>
                <InviteDialog invite={invite} actions={actions} {...{ user, expire }}>
                  <InviteCard invite={invite} user={user} expire={expire} />
                </InviteDialog>
              </AnimatedItem>
            ))}
            {tables.data.map((table) => (
              <AnimatedItem key={`table-${table.id}`} isHorizontal className={CARD_SLOT}>
                <TableCard
                  table={table}
                  inPlay={inPlay.has(table.id)}
                  user={user}
                  expire={expire}
                />
              </AnimatedItem>
            ))}
          </AnimatedList>
        </ScrollContainer>
      )}
    </section>
  );
}

const CARD_SLOT = "w-72 max-w-[85%] shrink-0 snap-start lg:w-full lg:max-w-none";
const CARD =
  "group flex h-56 w-full min-w-0 flex-col gap-3 rounded-xl border p-4 text-left lg:h-auto lg:flex-row lg:items-center";

function CardBody({
  emoji,
  emojiClass,
  name,
  badge,
  line,
  memberIds,
  chevronClass,
  user,
  expire,
}: Props & {
  emoji: string;
  emojiClass: string;
  name: string;
  badge: ReactNode;
  line: string;
  memberIds: string[];
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
          ids={memberIds}
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

function TableCard({ table, inPlay, user, expire }: Props & { table: Table; inPlay: boolean }) {
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
            {table.hostIds.includes(user.id) && (
              <span className="flex shrink-0 items-center gap-1 rounded-full border border-primary/60 px-1.5 font-mono text-[10px] font-bold uppercase text-primary">
                <Crown className="size-3" /> Host
              </span>
            )}
            {inPlay && <span className="sr-only">In play</span>}
          </>
        }
        line={line}
        memberIds={table.memberIds}
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
          <Mail className="size-3" /> Invite
        </span>
      }
      line={`${from?.name ?? "Someone"} wants you at this table. Accept?`}
      memberIds={invite.memberIds}
      chevronClass="text-invite"
      user={user}
      expire={expire}
    />
  );
}

/** The "Accept?" popup behind an invitation card. Accepting turns the card into the table's card. */
function InviteDialog({
  invite,
  actions,
  user,
  expire,
  children,
}: Props & { invite: TableInvite; actions: SocialActions; children: ReactNode }) {
  const from = usePerson(invite.fromId, user, expire, userKey(user.id, "people"));
  const blocked = actions.busy || !!actions.pending || !!actions.storageError;
  const count = invite.memberIds.length;
  return (
    <Dialog>
      <DialogTrigger asChild>
        <button
          type="button"
          aria-label={`Invitation to ${invite.tableName}`}
          className={cn(CARD, "border-invite/70 bg-invite/10 transition-colors hover:bg-invite/15")}
        >
          {children}
        </button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <span className="mx-auto flex size-14 items-center justify-center rounded-2xl bg-invite/20 text-3xl sm:mx-0">
            {invite.tableEmoji}
          </span>
          <DialogTitle className="font-display text-3xl font-black uppercase">
            Join {invite.tableName}?
          </DialogTitle>
          <DialogDescription>
            {from?.name ?? "Someone"} wants you at their table. You'll see its chat and can join its
            bets.
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <AvatarStack
            ids={invite.memberIds}
            max={4}
            user={user}
            expire={expire}
            queryKey={userKey(user.id, "people")}
          />
          {count} {count === 1 ? "person" : "people"} at the table
        </div>
        <DialogFooter className="gap-2">
          <DialogClose asChild>
            <Button
              variant="outline"
              disabled={blocked}
              onClick={() => actions.start({ kind: "invite", inviteId: invite.id, accept: false })}
            >
              Decline
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              className="bg-invite text-invite-foreground hover:bg-invite/90"
              disabled={blocked}
              onClick={() => actions.start({ kind: "invite", inviteId: invite.id, accept: true })}
            >
              Accept
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

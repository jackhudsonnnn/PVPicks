import { EmptyState } from "@/components/empty-state";
import { createFileRoute, Link } from "@tanstack/react-router";
import { ArrowRight, ChevronRight, Crown, Mail } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { myInvites, myTables, useAppStore, useMe, useGetUser } from "@/features/store";
import { TableInviteDialog } from "@/features/table/components/table-invite-dialog";
import { BetTicket } from "@/features/bets/components/bet-ticket";
import { PASS } from "@/features/bets/types";
import { AvatarStack } from "@/features/social/components/user-avatar";
import { CreateTableDialog } from "@/features/table/components/create-table-dialog";
import { isHost } from "@/features/table/hosts";
import { cn } from "@/lib/utils";
import { useCallback, useState } from "react";
import { AutoPageSentinel } from "@/components/auto-page-sentinel";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { Button } from "@/components/ui/button";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "P2Picks | Your tables and tickets" },
      {
        name: "description",
        content: "Live NFL and NBA scores, your open bet tickets, and every table you play at.",
      },
      { property: "og:title", content: "P2Picks | Your tables and tickets" },
      {
        property: "og:description",
        content: "Live NFL and NBA scores, your open bet tickets, and every table you play at.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: Home,
});

const PAGE = 10;

function Home() {
  const me = useMe();
  const getUser = useGetUser();
  const { state } = useAppStore();
  const tables = myTables(state);
  const invites = myInvites(state);
  const myTableIds = new Set(tables.map((t) => t.id));
  // Live first, then pending, resolved, washed; newest first within each group.
  const ORDER = { active: 0, pending: 1, resolved: 2, washed: 3 } as const;
  const tickets = state.bets
    .filter((b) => myTableIds.has(b.tableId))
    .map((b, i) => ({ b, i }))
    .sort((x, y) => ORDER[x.b.status] - ORDER[y.b.status] || y.i - x.i)
    .map(({ b }) => b);
  const [shown, setShown] = useState(PAGE);
  const loadMore = useCallback(() => setShown((n) => n + PAGE), []);
  const visible = tickets.slice(0, shown);

  return (
    <>
      <div className="mx-auto max-w-6xl space-y-10 px-4 py-6 md:px-8 md:py-10">
        <PageHeader eyebrow="Sunday slate" title={`Hey, ${getUser(me).username.split(" ")[0]}`} />

        <div className="grid grid-cols-1 gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
          {/* Tables */}
          <section className="min-w-0 space-y-3">
            <div className="flex items-center justify-between">
              <SectionTitle>Your tables</SectionTitle>
              <CreateTableDialog />
            </div>
            {tables.length === 0 && invites.length === 0 ? (
              <EmptyState boxed className="p-6">
                No tables yet. Create one and invite your friends.
              </EmptyState>
            ) : (
              <ScrollContainer isHorizontal="mobile" isVisible={false} className="snap-x snap-mandatory gap-3 py-1 lg:max-h-[27rem] lg:snap-none lg:gap-2 lg:rounded-2xl lg:border lg:bg-sidebar/60 lg:p-2">
                <AnimatedList>
                  {invites.map((inv) => (
                    <AnimatedItem key={`inv-${inv.id}`} isHorizontal className="w-72 max-w-[85%] shrink-0 snap-start lg:w-full lg:max-w-none">
                      <TableInviteDialog
                        invite={inv}
                        trigger={
                          <Button
                            type="button"
                            variant="ghost"
                            className="group flex h-56 w-full min-w-0 flex-col items-stretch justify-start gap-3 whitespace-normal rounded-lg border border-invite/70 bg-invite/10 p-4 text-left hover:bg-invite/15 hover:text-foreground lg:h-auto lg:flex-row lg:items-center lg:rounded-xl"
                          >
                            <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-invite/20 text-2xl lg:size-12 lg:rounded-xl">
                              {inv.table.emoji}
                            </span>
                            <div className="min-w-0 flex-1">
                              <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                                <p className="break-words font-display text-xl font-bold uppercase leading-none">
                                  {inv.table.name}
                                </p>
                                <span className="flex shrink-0 items-center gap-1 rounded-full bg-invite px-1.5 font-mono text-[10px] font-bold uppercase text-invite-foreground">
                                  <Mail className="size-3" /> Invite
                                </span>
                              </div>
                              <p className="mt-1.5 line-clamp-2 text-sm leading-snug text-muted-foreground">
                                {getUser(inv.fromId).username.split(" ")[0]} wants you at this table.
                                Accept?
                              </p>
                            </div>
                            <span className="flex shrink-0 items-center justify-between gap-3">
                              <AvatarStack ids={inv.table.memberIds} max={3} />
                              <ChevronRight className="size-4 shrink-0 text-invite transition-transform group-hover:translate-x-0.5" />
                            </span>
                          </Button>
                        }
                      />
                    </AnimatedItem>
                  ))}
                  {tables.map((t) => {
                    const msgs = state.messages.filter((m) => m.tableId === t.id);
                    const last = msgs[msgs.length - 1];
                    const hosting = isHost(t, me);
                    // Green row: you have a real pick (not Pass) in an open ticket here.
                    const inPlay = state.bets.some(
                      (b) =>
                        b.tableId === t.id &&
                        b.status === "active" &&
                        b.participants.some((p) => p.userId === me && p.selection !== PASS),
                    );
                    const preview = !last
                      ? "No messages yet"
                      : last.kind === "text"
                        ? `${getUser(last.authorId).username.split(" ")[0]}: ${last.text}`
                        : last.kind === "bet"
                          ? `${getUser(last.authorId).username.split(" ")[0]} sent a ticket`
                          : last.kind === "settlement"
                            ? "Points settled"
                            : last.kind === "gif"
                              ? `${getUser(last.authorId).username.split(" ")[0]} sent a GIF`
                              : last.text;
                    return (
                      <AnimatedItem key={`t-${t.id}`} isHorizontal className="w-72 max-w-[85%] shrink-0 snap-start lg:w-full lg:max-w-none">
                        <Link
                          to="/tables/$tableId"
                          params={{ tableId: t.id }}
                          className={cn(
                            "group flex h-56 min-w-0 flex-col gap-3 rounded-lg border p-4 transition-colors hover:border-primary/60 lg:h-auto lg:flex-row lg:items-center lg:rounded-xl",
                            inPlay
                              ? "border-primary/60 bg-primary/10 hover:bg-primary/15"
                              : "bg-surface hover:bg-surface-raised",
                          )}
                        >
                          <span
                            className={cn(
                              "flex size-10 shrink-0 items-center justify-center rounded-lg text-2xl lg:size-12 lg:rounded-xl",
                              inPlay ? "bg-primary/20" : "bg-muted",
                            )}
                          >
                            {t.emoji}
                          </span>
                          <div className="min-w-0 flex-1">
                            <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                              <p className="break-words font-display text-xl font-bold uppercase leading-none">
                                {t.name}
                              </p>
                              {hosting && (
                                <span className="flex shrink-0 items-center gap-1 rounded-full border border-primary/60 px-1.5 font-mono text-[10px] font-bold uppercase text-primary">
                                  <Crown className="size-3" /> Host
                                </span>
                              )}
                            </div>
                            <p className="mt-1.5 line-clamp-2 break-words text-sm leading-snug text-muted-foreground">
                              {preview}
                            </p>
                          </div>
                          <span className="flex shrink-0 items-center justify-between gap-3">
                            <AvatarStack ids={t.memberIds} max={3} />
                            <ChevronRight className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
                          </span>
                        </Link>
                      </AnimatedItem>
                    );
                  })}
                </AnimatedList>
              </ScrollContainer>
            )}
          </section>

          {/* Tickets (desktop only; phones use the Tickets page) */}
          <section className="hidden min-w-0 space-y-3 lg:block">
            <div className="flex items-center justify-between">
              <SectionTitle>Tickets</SectionTitle>
              <Link
                to="/tickets"
                className="flex h-8 items-center gap-1 rounded-md border px-3 text-xs font-semibold text-primary transition-colors hover:border-primary/60 hover:bg-surface-raised"
              >
                All tickets <ArrowRight className="size-3" />
              </Link>
            </div>
            {tickets.length === 0 ? (
              <EmptyState boxed className="p-6">
                No tickets yet. Head to a table and propose one.
              </EmptyState>
            ) : (
              <>
                <ScrollContainer
                  isVisible={false}
                  className="max-h-[21rem] grid grid-cols-1 gap-3 pb-1 lg:max-h-[27rem]"
                >
                  <AnimatedList>
                    {visible.map((b) => (
                      <AnimatedItem key={b.id} className="min-w-0">
                        <BetTicket bet={b} />
                      </AnimatedItem>
                    ))}
                  </AnimatedList>
                  <AutoPageSentinel
                    hasMore={shown < tickets.length}
                    pageKey={shown}
                    onLoadMore={loadMore}
                  />
                </ScrollContainer>
              </>
            )}
          </section>
        </div>
      </div>
    </>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="font-mono text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
      {children}
    </h2>
  );
}

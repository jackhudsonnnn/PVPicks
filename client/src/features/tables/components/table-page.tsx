import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { ArrowLeft, Crown, Info, MessageCircle, Plus, Spade, Ticket, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { cn } from "@/lib/utils";
import { backendErrorMessage } from "@/lib/api-client";
import { getTable, type Table, listTableBalances } from "@/features/tables/api";
import { BalanceDialog } from "./balance-dialog";
import { OthersDialog } from "./others-dialog";
import {
  listTableBets,
  ProposeBetSheet,
  BetTicket,
  useBetActions,
  proposeBlock,
  useBet,
  useHasTickets,
  BetDetailsDialog,
} from "@/features/bets";
import type { User } from "@/features/people";
import { ActionStatus } from "@/components/action-status";
import { AboutPanel, HostPanel } from "./host-panel";
import { ProfileDialog, useSocialActions } from "@/features/social";
import { TomatoLayer } from "./tomatoes";
import { TableChat } from "@/features/chat";
import { Felt } from "./felt";
import { PeersPanel } from "./peers";
import { userKey } from "@/lib/query-keys";
import { useRealtimeFeed } from "@/lib/realtime";
import { tableBetsFeed, tableViewFeed } from "@/lib/realtime-connection";
import { arrangeSeats, openBets } from "@/features/tables/seating";
import { useTableActions } from "@/features/tables/actions";
import { useExpireOnUnauthorized } from "@/lib/use-session-expiry";
import { LoadingPage } from "@/components/loading";

type TabId = "table" | "chat" | "peers" | "host" | "about";

const TABS = [
  { id: "table", label: "Table", icon: Spade },
  { id: "chat", label: "Chat", icon: MessageCircle },
  { id: "peers", label: "Peers", icon: Users },
  { id: "host", label: "Host", icon: Crown },
  { id: "about", label: "About", icon: Info },
] as const;

/** Loads the table, then lays out the felt and its side panel. */
export function TablePage({
  user,
  tableId,
  expire,
}: {
  user: User;
  tableId: string;
  expire: () => void;
}) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, tableId);
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getTable(tableId, signal),
    retry: false,
  });
  // Only the table itself; its bets and chat have their own feeds.
  useRealtimeFeed(tableViewFeed(tableId), (event) => {
    if (event.type === "table.changed")
      void queryClient.invalidateQueries({ queryKey: key, exact: true });
  });
  useExpireOnUnauthorized([query.error], expire);

  if (query.isPending) return <LoadingPage label="Loading table" />;
  if (query.isError) {
    return (
      <div className="mx-auto max-w-md space-y-3 px-4 py-20 text-center">
        <h1 className="font-display text-4xl font-black uppercase">Table unavailable</h1>
        <p role="alert">{backendErrorMessage(query.error)}</p>
        <div className="flex justify-center gap-2">
          <Button variant="outline" onClick={() => void query.refetch()}>
            Try again
          </Button>
          <Button asChild>
            <Link to="/">Back home</Link>
          </Button>
        </div>
      </div>
    );
  }
  return <TableLayout user={user} table={query.data} expire={expire} />;
}

function TableLayout({ user, table, expire }: { user: User; table: Table; expire: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const peopleKey = userKey(user.id, "people");
  const betsKey = userKey(user.id, table.id, "bets");
  const search = useRouterState({
    select: (state) => state.location.search as { tab?: string; profile?: string } | undefined,
  });
  const requestedTab = search?.tab;
  const requestedProfile = search?.profile;
  const [tab, setTab] = useState<TabId>(requestedTab === "peers" ? "peers" : "table");
  const [focusId, setFocusId] = useState<string | undefined>();
  const [openId, setOpenId] = useState<string | null>(null);
  const [proposing, setProposing] = useState(false);
  const [profileId, setProfileId] = useState<string | null>(null);
  const [balanceId, setBalanceId] = useState<string | null>(null);
  // A ticket's player avatars link here with ?tab=peers, even from this page's own ticket dialog.
  useEffect(() => {
    if (requestedTab !== "peers") return;
    setTab("peers");
    setOpenId(null);
  }, [requestedTab]);
  // A ticket's proposer avatar links here with ?profile=<id>, even from this page's own ticket dialog.
  useEffect(() => {
    if (!requestedProfile) return;
    setProfileId(requestedProfile);
    setOpenId(null);
  }, [requestedProfile]);
  // Switching tabs or closing the profile yourself drops the search, so the same link works again next time.
  const clearSearch = () => {
    if (requestedTab || requestedProfile)
      void navigate({ to: "/tables/$tableId", params: { tableId: table.id }, replace: true });
  };
  const chooseTab = (next: TabId) => {
    setTab(next);
    clearSearch();
  };

  const bets = useQuery({
    queryKey: betsKey,
    queryFn: ({ signal }) => listTableBets(table.id, signal),
    retry: false,
  });
  // Under the bets key, so every bet change (lock, result, points settlement) refreshes balances too.
  const balances = useQuery({
    queryKey: [...betsKey, "balances"],
    queryFn: ({ signal }) => listTableBalances(table.id, signal),
    retry: false,
  });
  useRealtimeFeed(tableBetsFeed(table.id), () => {
    void queryClient.invalidateQueries({ queryKey: betsKey });
  });
  const unauthorized = useExpireOnUnauthorized([bets.error, balances.error], expire);

  const betActions = useBetActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  // One instance per table page, so every members and settings control shares its pending state.
  const tableActions = useTableActions(
    user.id,
    async (intent) => {
      if (intent?.kind === "delete") {
        await queryClient.invalidateQueries({ queryKey: userKey(user.id, "list") });
        void navigate({ to: "/" });
        return;
      }
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );

  // One social-actions instance for the page: reports and blocks from chat and profiles share its retry state.
  const socialActions = useSocialActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
    },
    expire,
  );
  const openBet = (id: string) => {
    const bet = bets.data?.find((b) => b.id === id);
    // Open bets are focused on the felt as well, so closing the ticket leaves you on it.
    if (bet && (bet.status === "ACTIVE" || bet.status === "PENDING")) setFocusId(id);
    setOpenId(id);
  };
  const hosting = table.hostIds.includes(user.id);
  const block = proposeBlock(table, user.id);
  const hasTickets = useHasTickets(user.id);
  const deck = openBets(bets.data ?? []);
  const focused = deck.find((bet) => bet.id === focusId) ?? deck[0];
  // Live bets come from the list; older ones (opened from chat or a profile) are fetched.
  const opened = useBet(user.id, table.id, openId, bets.data, expire);
  // "+N others" on the felt: the focused ticket's Details when the felt shows its players, else who didn't fit.
  const { rail, fromBet } = arrangeSeats(table.memberIds, user.id, focused);
  const [othersOpen, setOthersOpen] = useState(false);
  const [railOpen, setRailOpen] = useState(false);
  const tabs = TABS.filter((t) => (t.id === "host" ? hosting : t.id === "about" ? !hosting : true));
  const sideTabs = tabs.filter((t) => t.id !== "table");
  // Falls back to chat if your host role changes while its tab is open.
  const view: TabId = (tab === "host" && !hosting) || (tab === "about" && hosting) ? "chat" : tab;

  return (
    <div className="flex h-[calc(100dvh-var(--app-top-height)-var(--app-bottom-height))] min-h-0 flex-col overflow-hidden">
      <div className="glass-bar grid shrink-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-2 border-b px-3 py-2 md:gap-3 md:px-6 md:py-3">
        <div className="flex min-w-0 items-center gap-3">
          <Button asChild variant="ghost" size="icon" className="-ml-2 shrink-0 nav:hidden">
            <Link to="/" aria-label="Back">
              <ArrowLeft />
            </Link>
          </Button>
          <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-muted text-lg md:size-10 md:text-xl">
            {table.emoji}
          </span>
          <h1 className="line-clamp-2 min-w-0 break-words text-lg font-black uppercase leading-none sm:text-2xl">
            {table.name}
          </h1>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {hasTickets ? (
            <Button asChild variant="outline" className="h-10 px-3">
              <Link to="/tickets" search={{ table: table.id }} aria-label="This table's tickets">
                <Ticket /> <span className="hidden sm:inline">Tickets</span>
              </Link>
            </Button>
          ) : (
            <Button
              variant="outline"
              className="h-10 px-3"
              disabled
              aria-label="This table's tickets"
              title="Tickets show up once a bet starts at one of your tables."
            >
              <Ticket /> <span className="hidden sm:inline">Tickets</span>
            </Button>
          )}
          <Button
            variant="hero"
            className="h-10 shrink-0 px-4"
            disabled={!!block}
            title={block ?? undefined}
            onClick={() => setProposing(true)}
          >
            <Plus /> Bet
          </Button>
        </div>
      </div>

      <SegmentedTabs
        ariaLabel="Table sections"
        className="shrink-0 rounded-none border-b split:hidden"
        tabs={tabs}
        value={view}
        onChange={chooseTab}
      />

      <div className="flex min-h-0 flex-1">
        <div
          role="region"
          aria-label="Table"
          className={cn(
            "relative min-h-0 min-w-0 flex-1 overflow-hidden px-2 split:block md:px-8",
            view === "table" ? "block" : "hidden",
          )}
        >
          <div className="absolute inset-x-2 top-2 z-20 md:inset-x-8">
            <ActionStatus
              actions={betActions}
              pendingText="A bet action may not have been saved."
              checkText="Check the table's bets and your pick first."
            />
            <ActionStatus
              actions={socialActions}
              pendingText="A report or block may not have been saved."
              checkText="Check your blocks first; reports can be sent again."
            />
            {bets.isError && !unauthorized && (
              <p role="alert" className="rounded-lg border bg-surface p-3 text-sm">
                {backendErrorMessage(bets.error)}{" "}
                <button className="underline" onClick={() => void bets.refetch()}>
                  Try again
                </button>
              </p>
            )}
          </div>
          <Felt
            table={table}
            bets={deck}
            focused={focused}
            onFocus={setFocusId}
            onOpenBet={openBet}
            // With a ticket's players around the felt, "+n" opens that ticket's Details (everyone in it).
            onOpenOthers={() => (fromBet && focused ? setOthersOpen(true) : setRailOpen(true))}
            onSelectUser={setProfileId}
            onPropose={block ? undefined : () => setProposing(true)}
            user={user}
            expire={expire}
            peopleKey={peopleKey}
          />
        </div>

        <aside
          className={cn(
            "min-h-0 w-full flex-col bg-sidebar/60 split:flex split:w-[400px] split:shrink-0 split:border-l",
            view !== "table" ? "flex" : "hidden",
          )}
        >
          <SegmentedTabs
            size="sm"
            ariaLabel="Side panel"
            className="hidden rounded-none border-b bg-transparent split:grid"
            tabs={sideTabs}
            value={view === "table" ? "chat" : view}
            onChange={chooseTab}
          />
          {view === "peers" ? (
            <PeersPanel
              table={table}
              balances={balances}
              onSelectUser={setProfileId}
              onSelectBalance={setBalanceId}
              className="flex-1"
              user={user}
              expire={expire}
              peopleKey={peopleKey}
            />
          ) : view === "host" ? (
            <HostPanel
              table={table}
              bets={bets.data}
              balances={balances.data}
              tableActions={tableActions}
              betActions={betActions}
              user={user}
              expire={expire}
              peopleKey={peopleKey}
              className="min-h-0 flex-1"
            />
          ) : view === "about" ? (
            <AboutPanel
              table={table}
              bets={bets.data}
              balances={balances.data}
              tableActions={tableActions}
              betActions={betActions}
              user={user}
              expire={expire}
              peopleKey={peopleKey}
              className="min-h-0 flex-1"
            />
          ) : (
            <TableChat
              user={user}
              table={table}
              expire={expire}
              fill
              onOpenBet={openBet}
              onSelectUser={setProfileId}
              social={socialActions}
            />
          )}
        </aside>
      </div>

      <ProposeBetSheet
        open={proposing}
        onOpenChange={setProposing}
        table={table}
        user={user}
        expire={expire}
        peopleKey={peopleKey}
        actions={betActions}
      />
      <ProfileDialog
        userId={profileId}
        table={table}
        user={user}
        expire={expire}
        peopleKey={peopleKey}
        social={socialActions}
        onOpenBet={openBet}
        onOpenChange={(open) => {
          if (open) return;
          setProfileId(null);
          clearSearch();
        }}
      />
      <OthersDialog
        ids={rail}
        open={railOpen}
        onOpenChange={setRailOpen}
        onSelectUser={(id) => {
          setRailOpen(false);
          setProfileId(id);
        }}
        user={user}
        expire={expire}
        peopleKey={peopleKey}
      />
      {focused && (
        <BetDetailsDialog
          bet={focused}
          table={table}
          user={user}
          expire={expire}
          peopleKey={peopleKey}
          open={othersOpen}
          onOpenChange={setOthersOpen}
        />
      )}
      <BalanceDialog
        tableId={table.id}
        memberId={balanceId}
        betsKey={betsKey}
        onOpenChange={(open) => !open && setBalanceId(null)}
        user={user}
        expire={expire}
        peopleKey={peopleKey}
      />
      <TomatoLayer tableId={table.id} user={user} expire={expire} peopleKey={peopleKey} />
      <Dialog open={!!opened} onOpenChange={(open) => !open && setOpenId(null)}>
        <DialogContent className="max-h-[90dvh] max-w-md overflow-y-auto border-0 bg-transparent p-0 shadow-none">
          <DialogTitle className="sr-only">{opened?.question ?? "Ticket"}</DialogTitle>
          {opened && (
            <BetTicket
              bet={opened}
              table={table}
              user={user}
              expire={expire}
              peopleKey={peopleKey}
              actions={betActions}
              className="max-w-none"
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

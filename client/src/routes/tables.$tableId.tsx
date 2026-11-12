import { createFileRoute, Link } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { ArrowLeft, Crown, Ticket, Info, MessageCircle, Plus, Spade, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { useAppStore, useMe } from "@/features/store";
import { BetTicket } from "@/features/bets/components/bet-ticket";
import { ProposeBetSheet } from "@/features/bets/components/propose-bet-sheet";
import type { Bet } from "@/features/bets/types";
import { PokerTable } from "@/features/table/components/poker-table";
import { ChatPanel } from "@/features/table/components/chat-panel";
import { MembersList } from "@/features/table/components/members-list";
import { OthersDialog } from "@/features/table/components/others-dialog";
import { ProfileDialog } from "@/features/table/components/profile-dialog";
import { AboutPanel } from "@/features/table/components/about-panel";
import { HostPanel } from "@/features/table/components/host-panel";
import { canProposeBet, hasPermission, isHost } from "@/features/table/hosts";
import { arrangeSeats } from "@/features/table/seating";

export const Route = createFileRoute("/tables/$tableId")({
  head: () => ({
    meta: [
      { title: "Table | P2Picks" },
      {
        name: "description",
        content: "Take a seat, chat with your tablemates and bet on live NFL and NBA games.",
      },
      { property: "og:title", content: "Table | P2Picks" },
      {
        property: "og:description",
        content: "Take a seat, chat with your tablemates and bet on live NFL and NBA games.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  // bet: focus/open a ticket. tab=peers: open the Peers tab. profile: open that person's profile.
  validateSearch: (
    search: Record<string, unknown>,
  ): { bet?: string; profile?: string; tab?: "peers" } => {
    const out: { bet?: string; profile?: string; tab?: "peers" } = {};
    if (search["tab"] === "peers") out.tab = "peers";
    for (const k of ["bet", "profile"] as const)
      if (typeof search[k] === "string") out[k] = search[k] as string;
    return out;
  },
  component: TablePage,
});

const PHONE_TABS = [
  { id: "table", label: "Table", icon: Spade },
  { id: "chat", label: "Chat", icon: MessageCircle },
  { id: "members", label: "Peers", icon: Users },
  { id: "host", label: "Host", icon: Crown },
  { id: "about", label: "About", icon: Info },
] as const;
type TabId = (typeof PHONE_TABS)[number]["id"];

function TablePage() {
  const me = useMe();
  const { tableId } = Route.useParams();
  const { state } = useAppStore();
  const table = state.tables.find((t) => t.id === tableId);
  const [tab, setTab] = useState<TabId>("table");
  const [focusId, setFocusId] = useState<string | undefined>();
  const [proposing, setProposing] = useState(false);
  const [profileId, setProfileId] = useState<string | null>(null);
  const [othersOpen, setOthersOpen] = useState(false);
  const [viewBet, setViewBet] = useState<Bet | null>(null);
  const {
    bet: linkedBetId,
    profile: linkedProfile,
    tab: linkedTab,
  } = Route.useSearch();

  // Opened from a ticket's player avatars: show the Peers tab.
  useEffect(() => {
    if (linkedTab !== "peers") return;
    setViewBet(null);
    setTab("members");
  }, [linkedTab, tableId]);

  // Opened from a settle-up card: show that person's profile.
  useEffect(() => {
    if (linkedProfile) setProfileId(linkedProfile);
  }, [linkedProfile, tableId]);

  // Opened from a ticket link: focus that ticket on the felt, or open it if it's closed.
  // Runs again if the ticket shows up after the page opened (e.g. data still loading).
  const linkedBet = linkedBetId
    ? state.bets.find((x) => x.id === linkedBetId && x.tableId === tableId)
    : undefined;
  const hasLinkedBet = !!linkedBet;
  useEffect(() => {
    const b = linkedBet;
    if (!b) return;
    if (b.status === "active" || b.status === "pending") {
      setFocusId(b.id);
      setTab("table");
    } else setViewBet(b);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only when the link or its ticket appears
  }, [linkedBetId, tableId, hasLinkedBet]);

  if (!table) {
    return (
      <div className="mx-auto max-w-md px-4 py-20 text-center">
        <h1 className="text-4xl font-black uppercase">Table not found</h1>
        <p className="mt-2 text-sm text-muted-foreground">It may have been closed.</p>
        <Button asChild className="mt-6">
          <Link to="/">Back home</Link>
        </Button>
      </div>
    );
  }

  // Newest open bets first; focus falls back to the newest when nothing is chosen.
  const openBets = state.bets
    .filter((b) => b.tableId === table.id && (b.status === "active" || b.status === "pending"))
    .reverse();
  const focused = openBets.find((b) => b.id === focusId) ?? openBets[0];
  const live = viewBet ? (state.bets.find((b) => b.id === viewBet.id) ?? viewBet) : null;
  const hosting = isHost(table, me);
  const canBet = canProposeBet(table, me);
  const phoneTabs = PHONE_TABS.filter((t) =>
    t.id === "host" ? hosting : t.id === "about" ? !hosting : true,
  );
  const sideTabs = phoneTabs.filter((t) => t.id !== "table");
  // Falls back to chat if you stop being a host while on the Host tab.
  const view: TabId = (tab === "host" && !hosting) || (tab === "about" && hosting) ? "chat" : tab;
  const { seated, rail } = arrangeSeats(table.memberIds, me, focused);

  function selectUser(id: string) {
    setProfileId(id);
  }

  function openBetFromChat(bet: Bet) {
    if (bet.status === "active" || bet.status === "pending") {
      setFocusId(bet.id);
      setTab("table");
    } else {
      setViewBet(bet);
    }
  }

  return (
    <div className="flex h-[calc(100dvh-var(--app-top-height)-var(--app-bottom-height))] min-h-0 flex-col overflow-hidden">
      {/* Header */}
      <div className="glass-bar grid shrink-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-2 border-b px-3 py-2 md:gap-3 md:px-6 md:py-3">
        <div className="flex min-w-0 items-center gap-3">
          <Button asChild variant="ghost" size="icon" className="-ml-2 shrink-0 md:hidden">
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
        <Button asChild variant="outline" className="h-10 px-3">
          <Link to="/tickets" search={{ table: table.id }} aria-label="This table's tickets">
            <Ticket /> <span className="hidden sm:inline">Tickets</span>
          </Link>
        </Button>
        <Button
          variant="hero"
          className="h-10 shrink-0 px-4"
          disabled={!canBet}
          title={
            canBet
              ? undefined
              : hasPermission(table, me, "bets")
                ? "Only hosts can start bets at this table"
                : "A host turned off betting for you at this table"
          }
          onClick={() => setProposing(true)}
        >
          <Plus /> Bet
        </Button>
        </div>
      </div>

      {/* Phone tabs */}
      <SegmentedTabs
        ariaLabel="Table sections"
        className="shrink-0 rounded-none border-b md:hidden"
        tabs={phoneTabs}
        value={view}
        onChange={setTab}
      />

      <div className="flex min-h-0 flex-1">
        {/* Table */}
        <div
          role="region"
          aria-label="Table"
          className={cn(
            "min-h-0 min-w-0 flex-1 overflow-hidden px-2 md:block md:px-8",
            view === "table" ? "block" : "hidden",
          )}
        >
          <PokerTable
            table={table}
            openBets={openBets}
            focused={focused}
            onFocus={setFocusId}
            onOpenBet={(id) => {
              const b = openBets.find((x) => x.id === id);
              if (b) setViewBet(b);
            }}
            onSelectUser={selectUser}
            onOpenOthers={() => setOthersOpen(true)}
            onPropose={canBet ? () => setProposing(true) : undefined}
          />
        </div>

        {/* Chat */}
        <aside
          className={cn(
            "min-h-0 w-full flex-col border-l bg-sidebar/60 md:flex md:w-[360px] md:shrink-0 lg:w-[400px]",
            view !== "table" ? "flex" : "hidden",
          )}
        >
          <SegmentedTabs
            size="sm"
            ariaLabel="Side panel"
            className="hidden rounded-none border-b bg-transparent md:grid"
            tabs={sideTabs}
            value={view === "table" ? "chat" : view}
            onChange={setTab}
          />
          {view === "about" ? (
            <AboutPanel table={table} onOpenPeers={() => setTab("members")} className="flex-1" />
          ) : view === "host" ? (
            <HostPanel table={table} className="flex-1" />
          ) : view === "members" ? (
            <MembersList
              table={table}
              seatedIds={seated}
              onSelectUser={selectUser}
              className="flex-1"
            />
          ) : (
            <ChatPanel
              key={tableId}
              tableId={table.id}
              onBetClick={openBetFromChat}
              onSelectUser={selectUser}
              className="flex-1"
            />
          )}
        </aside>
      </div>

      <ProposeBetSheet tableId={table.id} open={proposing && canBet} onOpenChange={setProposing} />
      <OthersDialog
        ids={rail}
        bet={focused}
        open={othersOpen}
        onOpenChange={setOthersOpen}
        onSelectUser={(id) => {
          setOthersOpen(false);
          selectUser(id);
        }}
      />
      <ProfileDialog
        userId={profileId}
        table={table}
        onOpenChange={(o) => !o && setProfileId(null)}
        onThrow={() => {
          if (window.matchMedia("(max-width: 767px)").matches) setTab("table");
        }}
      />
      <Dialog open={!!live} onOpenChange={(o) => !o && setViewBet(null)}>
        <DialogContent className="max-w-md border-0 bg-transparent p-0 shadow-none">
          <DialogTitle className="sr-only">Bet ticket</DialogTitle>
          {live && <BetTicket bet={live} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

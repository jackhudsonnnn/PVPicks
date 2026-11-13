import { Link, useRouterState } from "@tanstack/react-router";
import { BookOpen, Home, LogOut, SlidersHorizontal, Ticket, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/features/auth/auth-provider";
import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { Logo } from "@/components/logo";
import { myInvites, myTables, useAppStore, useMe } from "@/features/store";
import { TableInviteDialog } from "@/features/table/components/table-invite-dialog";
import { formatPts } from "@/features/bets/ledger";
import { netSettleUps } from "@/features/table/settlements";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { LiveTicker } from "@/features/bets/components/live-ticker";

const NAV = [
  { to: "/", label: "Home", icon: Home },
  { to: "/tickets", label: "Tickets", icon: Ticket },
  { to: "/friends", label: "Friends", icon: Users },
  { to: "/how-to-play", label: "How to play", icon: BookOpen },
  { to: "/preferences", label: "Preferences", icon: SlidersHorizontal },
] as const;

/** Net still owed to (+) or by (-) you until settle-ups are confirmed. Opens Friends > Settle-ups. */
function PointsPill() {
  const me = useMe();
  const { state } = useAppStore();
  const pts = netSettleUps(state.messages, me);
  return (
    <Link
      to="/friends"
      aria-label={`${formatPts(pts, true)} net in open settle-ups. View settle-ups`}
      title="Net of your open settle-ups. Tap to see them"
      className="flex items-center gap-2 rounded-full border bg-surface px-3 py-1 transition-colors hover:border-primary/60 hover:bg-surface-raised"
    >
      <span
        className={cn(
          "scoreboard text-lg leading-none",
          pts > 0 ? "text-primary" : pts < 0 ? "text-loss" : "text-muted-foreground",
        )}
      >
        {formatPts(pts, true)}
      </span>
      <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        pts
      </span>
    </Link>
  );
}

function SignOutButton() {
  const { signOut } = useAuth();
  return (
    <Button
      size="icon"
      variant="ghost"
      onClick={() => signOut()}
      aria-label="Sign out"
      title="Sign out"
    >
      <LogOut />
    </Button>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const me = useMe();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const isTable = pathname.startsWith("/tables/");
  const { state } = useAppStore();
  const isActive = (to: string) => (to === "/" ? pathname === "/" : pathname.startsWith(to));
  const seatIds = new Set(state.tables.filter((t) => t.memberIds.includes(me)).map((t) => t.id));
  const activeCount = state.bets.filter(
    (b) => b.status === "active" && seatIds.has(b.tableId),
  ).length;

  return (
    <div className={cn(
      "min-h-dvh bg-field [--app-bottom-height:4rem] md:[--app-bottom-height:0rem]",
      isTable ? "[--app-top-height:3.5rem] md:[--app-top-height:0rem]" : "[--app-top-height:6rem] md:[--app-top-height:2.5rem]",
    )}>
      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r bg-sidebar md:flex">
        <div className="flex h-16 items-center justify-between pl-5 pr-3">
          <Logo />
          <div className="flex items-center gap-1">
            <SignOutButton />
          </div>
        </div>
        <nav className="flex flex-col gap-1 px-3 py-2">
          {NAV.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={to}
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors",
                isActive(to)
                  ? "bg-sidebar-accent text-foreground"
                  : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
              )}
            >
              <Icon className={cn("size-4", isActive(to) && "text-primary")} />
              {label}
              {to === "/tickets" && activeCount > 0 && (
                <span className="ml-auto rounded-full bg-primary px-1.5 font-mono text-[10px] font-bold text-primary-foreground">
                  {activeCount}
                </span>
              )}
            </Link>
          ))}
        </nav>
        {(myInvites(state).length > 0 || myTables(state).length > 0) && (
          <>
            <div className="mt-4 px-5 font-mono text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
              Your tables
            </div>
            <ScrollContainer isVisible={false} className="flex flex-col gap-0.5 px-3 py-2">
              <AnimatedList>
                {myInvites(state).map((inv) => (
                  <AnimatedItem key={`inv-${inv.id}`} className="flex flex-col">
                    <TableInviteDialog
                      invite={inv}
                      trigger={
                        <button
                          type="button"
                          className="flex items-center gap-3 rounded-lg border border-invite/50 bg-invite/10 px-3 py-2 text-left text-sm text-invite transition-colors hover:bg-invite/20"
                        >
                          <span className="text-base">{inv.table.emoji}</span>
                          <span className="min-w-0 flex-1 truncate">{inv.table.name}</span>
                          <span className="font-mono text-[10px] font-bold uppercase">Invite</span>
                        </button>
                      }
                    />
                  </AnimatedItem>
                ))}
                {myTables(state).map((t) => (
                  <AnimatedItem key={`t-${t.id}`} className="flex flex-col">
                    <Link
                      to="/tables/$tableId"
                      params={{ tableId: t.id }}
                      className={cn(
                        "flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors",
                        pathname === `/tables/${t.id}`
                          ? "bg-sidebar-accent text-foreground"
                          : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                      )}
                    >
                      <span className="text-base">{t.emoji}</span>
                      <span className="truncate">{t.name}</span>
                    </Link>
                  </AnimatedItem>
                ))}
              </AnimatedList>
            </ScrollContainer>
          </>
        )}
        <div className="mt-auto border-t p-4">
          <div className="flex items-center gap-1">
            <PointsPill />
          </div>
          <p className="mt-2 text-[11px] leading-snug text-muted-foreground">
            Net of your open settle-ups: pay, get paid or forgive to clear them. Points are mock
            dollars.
          </p>
        </div>
      </aside>

      {/* Mobile top bar */}
      <div className="sticky top-0 z-30 md:pl-64">
      <header className="glass-bar flex h-14 items-center justify-between border-b px-4 md:hidden">
        <Logo />
        <div className="flex items-center gap-1">
          <PointsPill />
          <SignOutButton />
        </div>
      </header>
      {!isTable && <LiveTicker />}
      </div>

      <main className={cn("md:pl-64", !isTable && "pb-20 md:pb-0")}>{children}</main>

      {/* Mobile tab bar */}
      <nav className="glass-bar fixed inset-x-0 bottom-0 z-30 grid h-16 grid-cols-5 border-t md:hidden">
        {NAV.map(({ to, label, icon: Icon }) => (
          <Link
            key={to}
            to={to}
            className={cn(
              "relative flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider",
              isActive(to) ? "text-primary" : "text-muted-foreground",
            )}
          >
            <Icon className="size-5" />
            {label === "How to play" ? "Guide" : label === "Preferences" ? "Prefs" : label}
            {to === "/tickets" && activeCount > 0 && (
              <span className="absolute right-1/2 top-2 translate-x-4 rounded-full bg-primary px-1 font-mono text-[9px] font-bold text-primary-foreground">
                {activeCount}
              </span>
            )}
          </Link>
        ))}
      </nav>
    </div>
  );
}

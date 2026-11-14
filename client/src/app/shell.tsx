import type { ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, Home, LogOut, SlidersHorizontal, Ticket, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/logo";
import { ScrollContainer } from "@/components/scroll-container";
import { AnimatedItem, AnimatedList } from "@/components/animated-list";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { listTables, type BackendUser } from "./backend-api";
import { BackendTicker } from "./backend-ticker";
import { useRealtimeFeed, useRealtimeStatus } from "./realtime";
import { settlementsFeed } from "./realtime-connection";
import { listSettlementGroups } from "./settlement-api";
import { useInvites } from "./social-queries";
import { userKey } from "./query-keys";

const NAV = [
  { to: "/", label: "Home", short: "Home", icon: Home },
  { to: "/tickets", label: "Tickets", short: "Tickets", icon: Ticket },
  { to: "/friends", label: "Friends", short: "Friends", icon: Users },
  { to: "/how-to-play", label: "How to play", short: "Guide", icon: BookOpen },
  { to: "/preferences", label: "Preferences", short: "Prefs", icon: SlidersHorizontal },
] as const;

const isActive = (pathname: string, to: string) =>
  to === "/" ? pathname === "/" : pathname.startsWith(to);

/**
 * Net of your open settle-ups: positive means you're owed. Same query and key as the Friends page's settle-ups,
 * so both stay in step; the settlements feed refreshes it.
 */
function PointsPill({ user }: { user: BackendUser }) {
  const queryClient = useQueryClient();
  const key = userKey(user.id, "settlements");
  useRealtimeFeed(settlementsFeed, () => void queryClient.invalidateQueries({ queryKey: key }));
  const groups = useQuery({
    queryKey: [...key, "groups"],
    queryFn: ({ signal }) => listSettlementGroups(user.id, signal),
    retry: false,
  });
  const net = groups.data?.reduce((sum, group) => sum + group.netCents, 0n);
  const label = net === undefined ? "–" : formatCents(net, true);
  return (
    <Link
      to="/friends"
      aria-label={`${label} points net in open settle-ups. View settle-ups`}
      title="Net of your open settle-ups. Tap to see them"
      className="flex items-center gap-2 rounded-full border bg-surface px-3 py-1 transition-colors hover:border-primary/60 hover:bg-surface-raised"
    >
      <span
        className={cn(
          "scoreboard text-lg leading-none",
          net !== undefined && net > 0n
            ? "text-primary"
            : net !== undefined && net < 0n
              ? "text-loss"
              : "text-muted-foreground",
        )}
      >
        {label}
      </span>
      <span className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground">
        pts
      </span>
    </Link>
  );
}

function LiveStatus() {
  const status = useRealtimeStatus();
  if (!status) return null;
  return (
    <span
      role="status"
      className="font-mono text-[10px] uppercase tracking-widest text-muted-foreground"
    >
      {status === "live" ? "Live" : "Reconnecting…"}
    </span>
  );
}

function SignOutButton({ onSignOut, busy }: { onSignOut: () => void; busy: boolean }) {
  return (
    <Button
      size="icon"
      variant="ghost"
      onClick={onSignOut}
      disabled={busy}
      aria-label="Sign out"
      title="Sign out"
    >
      <LogOut />
    </Button>
  );
}

/** Your tables and pending invitations for the sidebar; invitations open Home, where you answer them. */
function SidebarTables({ user, pathname }: { user: BackendUser; pathname: string }) {
  const tables = useQuery({
    queryKey: userKey(user.id, "list"),
    queryFn: ({ signal }) => listTables(signal),
    retry: false,
  });
  const invites = useInvites(user.id);
  if (!tables.data?.length && !invites.data?.length) return null;
  return (
    <>
      <div className="mt-4 px-5 font-mono text-[10px] font-bold uppercase tracking-widest text-muted-foreground">
        Your tables
      </div>
      <ScrollContainer isVisible={false} className="flex flex-col gap-0.5 px-3 py-2">
        <AnimatedList>
          {(invites.data ?? []).map((invite) => (
            <AnimatedItem key={`invite-${invite.id}`} className="flex flex-col">
              <Link
                to="/"
                className="flex items-center gap-3 rounded-lg border border-invite/50 bg-invite/10 px-3 py-2 text-left text-sm text-invite transition-colors hover:bg-invite/20"
              >
                <span className="text-base">{invite.tableEmoji}</span>
                <span className="min-w-0 flex-1 truncate">{invite.tableName}</span>
                <span className="font-mono text-[10px] font-bold uppercase">Invite</span>
              </Link>
            </AnimatedItem>
          ))}
          {(tables.data ?? []).map((table) => (
            <AnimatedItem key={`table-${table.id}`} className="flex flex-col">
              <Link
                to="/tables/$tableId"
                params={{ tableId: table.id }}
                aria-current={pathname === `/tables/${table.id}` ? "page" : undefined}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors",
                  pathname === `/tables/${table.id}`
                    ? "bg-sidebar-accent text-foreground"
                    : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                )}
              >
                <span className="text-base">{table.emoji}</span>
                <span className="truncate">{table.name}</span>
              </Link>
            </AnimatedItem>
          ))}
        </AnimatedList>
      </ScrollContainer>
    </>
  );
}

/**
 * The signed-in frame: a sidebar on desktop (navigation, your tables, net points), a top bar and bottom tab bar on
 * phones, and the score ticker everywhere except a table page.
 */
export function AppShell({
  user,
  pathname,
  expire,
  onSignOut,
  signingOut,
  children,
}: {
  user: BackendUser;
  pathname: string;
  expire: () => void;
  onSignOut: () => void;
  signingOut: boolean;
  children: ReactNode;
}) {
  const isTable = pathname.startsWith("/tables/");
  return (
    <div
      className={cn(
        "min-h-dvh bg-field [--app-bottom-height:4rem] md:[--app-bottom-height:0rem]",
        isTable
          ? "[--app-top-height:3.5rem] md:[--app-top-height:0rem]"
          : "[--app-top-height:6rem] md:[--app-top-height:2.5rem]",
      )}
    >
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r bg-sidebar md:flex">
        <div className="flex h-16 items-center justify-between pl-5 pr-3">
          <Logo />
          <SignOutButton onSignOut={onSignOut} busy={signingOut} />
        </div>
        <nav aria-label="Main" className="flex flex-col gap-1 px-3 py-2">
          {NAV.map(({ to, label, icon: Icon }) => (
            <Link
              key={to}
              to={to}
              aria-current={isActive(pathname, to) ? "page" : undefined}
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors",
                isActive(pathname, to)
                  ? "bg-sidebar-accent text-foreground"
                  : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
              )}
            >
              <Icon className={cn("size-4", isActive(pathname, to) && "text-primary")} />
              {label}
            </Link>
          ))}
        </nav>
        <SidebarTables user={user} pathname={pathname} />
        <div className="mt-auto border-t p-4">
          <div className="flex items-center justify-between gap-2">
            <PointsPill user={user} />
            <LiveStatus />
          </div>
          <p className="mt-2 text-[11px] leading-snug text-muted-foreground">
            Net of your open settle-ups: pay, get paid or forgive to clear them. Points are mock
            dollars.
          </p>
          <p className="mt-2 truncate text-xs text-muted-foreground">
            Signed in as {user.username}
          </p>
        </div>
      </aside>

      <div className="sticky top-0 z-30 md:pl-64">
        <header className="glass-bar flex h-14 items-center justify-between border-b px-4 md:hidden">
          <Logo />
          <div className="flex items-center gap-1">
            <PointsPill user={user} />
            <SignOutButton onSignOut={onSignOut} busy={signingOut} />
          </div>
        </header>
        {!isTable && <BackendTicker user={user} expire={expire} />}
      </div>

      <main className={cn("md:pl-64", !isTable && "pb-20 md:pb-0")}>{children}</main>

      <nav
        aria-label="Main (phone)"
        className="glass-bar fixed inset-x-0 bottom-0 z-30 grid h-16 grid-cols-5 border-t md:hidden"
      >
        {NAV.map(({ to, short, icon: Icon }) => (
          <Link
            key={to}
            to={to}
            className={cn(
              "relative flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider",
              isActive(pathname, to) ? "text-primary" : "text-muted-foreground",
            )}
          >
            <Icon className="size-5" />
            {short}
          </Link>
        ))}
      </nav>
    </div>
  );
}

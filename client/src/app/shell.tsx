import type { ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { BookOpen, LogOut, SlidersHorizontal, Ticket, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/logo";
import { formatCents } from "@/lib/money";
import { cn } from "@/lib/utils";
import { SidebarTables } from "@/features/tables";
import { useHasTickets } from "@/features/bets";
import type { User } from "@/features/people";
import { ScoreTicker } from "@/features/sports";
import { useRealtimeFeed, useRealtimeStatus } from "@/lib/realtime";
import { settlementsFeed } from "@/lib/realtime-connection";
import { listSettlementGroups } from "@/features/settlements";
import { userKey } from "@/lib/query-keys";
import { useDevAvailable } from "@/features/devdata";
import { DevLinks } from "./dev-links";

/** Custom SVG Icon for Home that inherits parent text color via fill="currentColor" */
function HomeSvgIcon({ className }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 1000 1000"
      fill="currentColor"
      className={className}
    >
      {/* Main Upper P-Shape */}
      <path
        d="
        M 10,800
        L 235,535
        C 265,500 305,480 355,480
        L 740,480
        C 790,480 830,445 830,400
        C 830,355 790,320 740,320
        L 240,320
        C 210,320 185,330 168,348
        C 172,328 182,308 198,288
        L 285,185
        C 312,153 345,140 390,140
        L 790,140
        C 905,140 990,235 990,355
        C 990,475 905,570 790,570
        L 365,570
        C 335,570 312,582 292,606
        L 206,800
        Z
      "
      />
      {/* Bottom Right Accent Bar */}
      <path
        d="
        M 300,800
        L 395,688
        C 418,660 448,648 485,648
        L 865,648
        C 890,648 910,640 924,625
        C 918,645 906,665 890,685
        L 835,750
        C 812,778 782,800 740,800
        Z
      "
      />
    </svg>
  );
}

const NAV = [
  { to: "/tickets", label: "Tickets", short: "Tickets", icon: Ticket },
  { to: "/friends", label: "Friends", short: "Friends", icon: Users },
  { to: "/", label: "Home", short: "", icon: HomeSvgIcon },
  { to: "/how-to-play", label: "Guide", short: "Guide", icon: BookOpen },
  { to: "/preferences", label: "Preferences", short: "Prefs", icon: SlidersHorizontal },
];

const NO_TICKETS = "Tickets show up once a bet starts at one of your tables.";

const isActive = (pathname: string, to: string) =>
  to === "/" ? pathname === "/" : pathname.startsWith(to);

/**
- Net of your open settle-ups: positive means you're up. Same query and key as the Friends page's settle-ups,
- so both stay in step; the settlements feed refreshes it.
 */
function PointsPill({ user }: { user: User }) {
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

/**
- The signed-in frame: a sidebar on desktop (navigation, your tables and requests, net points), a top bar and bottom tab bar on
- phones, and the score ticker everywhere except a table page.
 */
export function AppShell({
  user,
  pathname,
  expire,
  onSignOut,
  signingOut,
  children,
}: {
  user: User;
  pathname: string;
  expire: () => void;
  onSignOut: () => void;
  signingOut: boolean;
  children: ReactNode;
}) {
  const isTable = pathname.startsWith("/tables/");
  const devAvailable = useDevAvailable(user.id);
  const devActive = isActive(pathname, "/dev");
  // Tickets stays greyed out until there's a bet at one of your tables.
  const hasTickets = useHasTickets(user.id);
  const off = (to: string) => to === "/tickets" && !hasTickets && !isActive(pathname, to);
  return (
    <div
      className={cn(
        // Never wider than the screen: a sideways scroll lets phones zoom out and shift the page.
        "min-h-dvh overflow-x-clip bg-field [--app-bottom-height:4rem] nav:[--app-bottom-height:0rem]",
        isTable
          ? "[--app-top-height:3.5rem] nav:[--app-top-height:0rem]"
          : "[--app-top-height:6rem] nav:[--app-top-height:2.5rem]",
      )}
    >
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r bg-sidebar nav:flex">
        <div className="flex h-16 shrink-0 items-center justify-between pl-5 pr-3">
          <Logo badge={devAvailable && <DevLinks active={devActive} />} />
          <SignOutButton onSignOut={onSignOut} busy={signingOut} />
        </div>
        <nav aria-label="Main" className="flex shrink-0 flex-col gap-1 px-3 py-2">
          {NAV.map(({ to, label, icon: Icon }) => {
            const active = isActive(pathname, to);
            const isHome = to === "/";
            if (off(to))
              return (
                <span
                  key={to}
                  aria-disabled="true"
                  title={NO_TICKETS}
                  className="flex cursor-not-allowed items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold text-muted-foreground opacity-50"
                >
                  <Icon className="size-4" />
                  {label}
                </span>
              );
            return (
              <Link
                key={to}
                to={to}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors",
                  active
                    ? "bg-sidebar-accent text-foreground"
                    : isHome
                      ? "text-white hover:bg-sidebar-accent/60 hover:text-white"
                      : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                )}
              >
                <Icon
                  className={cn("size-4", active ? "text-primary" : isHome ? "text-white" : "")}
                />
                {label}
              </Link>
            );
          })}
        </nav>
        <SidebarTables user={user} pathname={pathname} expire={expire} />
        <div className="mt-auto shrink-0 border-t p-4">
          <div className="flex items-center justify-between gap-2">
            <PointsPill user={user} />
            <LiveStatus />
          </div>
          <p className="mt-2 text-[11px] leading-snug text-muted-foreground">
            Net of your open settle-ups. Points have no cash value.
          </p>
          <p className="mt-2 truncate text-xs text-muted-foreground">
            Signed in as {user.username}
          </p>
        </div>
      </aside>

      <div className="sticky top-0 z-30 nav:ml-64">
        <header className="glass-bar flex h-14 items-center justify-between gap-2 border-b px-3 nav:hidden">
          <Logo badge={devAvailable && <DevLinks active={devActive} compact />} />
          <div className="flex shrink-0 items-center gap-1">
            <PointsPill user={user} />
            <SignOutButton onSignOut={onSignOut} busy={signingOut} />
          </div>
        </header>
        {!isTable && <ScoreTicker user={user} expire={expire} />}
      </div>

      <main className={cn("nav:pl-64", !isTable && "pb-20 nav:pb-0")}>{children}</main>

      <nav
        aria-label="Main (phone)"
        className="glass-bar fixed inset-x-0 bottom-0 z-30 grid h-16 grid-cols-5 border-t nav:hidden"
      >
        {NAV.map(({ to, short, icon: Icon }) => {
          const active = isActive(pathname, to);
          const isHome = to === "/";
          if (off(to))
            return (
              <span
                key={to}
                aria-disabled="true"
                title={NO_TICKETS}
                className="flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground opacity-40"
              >
                <Icon className="size-5" />
                {short}
              </span>
            );
          return (
            <Link
              key={to}
              to={to}
              className={cn(
                "relative flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider",
                active ? "text-primary" : isHome ? "text-white" : "text-muted-foreground",
              )}
            >
              <Icon
                className={cn(
                  isHome ? "w-10 h-7" : "size-5",
                  active ? "text-primary" : isHome ? "text-white" : "",
                )}
              />
              {short}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}

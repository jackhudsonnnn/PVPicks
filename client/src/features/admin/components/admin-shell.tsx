import { useEffect, useState } from "react";
import { useNavigate, useRouterState } from "@tanstack/react-router";
import {
  BarChart3,
  HeartPulse,
  LogOut,
  MessageSquareWarning,
  ShieldCheck,
  UserSearch,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Logo } from "@/components/logo";
import { useAuth } from "@/features/auth/auth-provider";
import { useReports } from "@/features/social/reports-storage";
import { PeopleProvider } from "@/features/social/people";
import { ADMIN_DIRECTORY } from "../admin-data";
import { AdminDashboard } from "./admin-dashboard";
import { UserAuditPage } from "./user-audit-page";
import { HealthPage } from "./health-page";
import { ModerationPage } from "./moderation-page";

type Page = "aggregate" | "users" | "health" | "feedback";
const NAV: { id: Page; label: string; short: string; icon: typeof BarChart3 }[] = [
  { id: "aggregate", label: "Aggregate data", short: "Data", icon: BarChart3 },
  { id: "users", label: "User audit", short: "Users", icon: UserSearch },
  { id: "health", label: "Site health", short: "Health", icon: HeartPulse },
  { id: "feedback", label: "Feedback & reports", short: "Feedback", icon: MessageSquareWarning },
];

function AdminLogo() {
  return (
    <Logo
      asLink={false}
      badge={
        <span className="flex items-center gap-1 rounded-full bg-primary/15 px-2 py-0.5 font-mono text-[10px] font-bold uppercase tracking-widest text-primary">
          <ShieldCheck className="size-3" /> Admin
        </span>
      }
    />
  );
}

/** Who is signed in: the admin account's own person, not a player. */
function AdminIdentity() {
  const { session } = useAuth();
  const me = session?.persona;
  if (!me) return null;
  return (
    <div className="flex items-center gap-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-chart-5 font-display text-lg font-black uppercase text-primary-foreground">
        {me.username.charAt(0)}
      </span>
      <div className="min-w-0">
        <p className="truncate text-sm font-semibold">{me.username}</p>
        <p className="truncate font-mono text-[11px] text-muted-foreground">
          Moderator
        </p>
      </div>
    </div>
  );
}

/**
 * Frame for the admin demo account: same sidebar / bottom bar as players, with four admin pages.
 * It never renders player routes (they need a player's data) and keeps the address on /admin.
 */
export function AdminShell() {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const [page, setPage] = useState<Page>("aggregate");
  const { reports } = useReports();
  const openReports = reports.filter((r) => r.status === "open" || r.status === "reviewing").length;

  useEffect(() => {
    if (pathname !== "/admin") navigate({ to: "/admin", replace: true });
  }, [pathname, navigate]);
  // Braces matter: newer Chrome returns a Promise from scrollTo, and React would call it as cleanup.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [page]);

  const badge = (id: Page) => (id === "feedback" && openReports > 0 ? openReports : null);

  return (
    <div className="min-h-dvh bg-field">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r bg-sidebar md:flex">
        <div className="flex h-16 items-center pl-5">
          <AdminLogo />
        </div>
        <nav className="flex flex-col gap-1 px-3 py-2">
          {NAV.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              onClick={() => setPage(id)}
              className={cn(
                "flex items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-semibold transition-colors",
                page === id
                  ? "bg-sidebar-accent text-foreground"
                  : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
              )}
            >
              <Icon className={cn("size-4", page === id && "text-primary")} />
              {label}
              {badge(id) && (
                <span className="ml-auto rounded-full bg-loss px-1.5 font-mono text-[10px] font-bold text-primary-foreground">
                  {badge(id)}
                </span>
              )}
            </button>
          ))}
        </nav>
        <div className="mt-auto space-y-3 border-t p-4">
          <AdminIdentity />
          <Button size="sm" variant="outline" className="w-full" onClick={() => signOut()}>
            <LogOut /> Sign out
          </Button>
        </div>
      </aside>

      <header className="glass-bar sticky top-0 z-30 flex h-14 items-center justify-between border-b px-4 md:hidden">
        <AdminLogo />
        <Button size="sm" variant="outline" onClick={() => signOut()} aria-label="Sign out">
          <LogOut />
        </Button>
      </header>

      <main className="pb-20 md:pb-0 md:pl-64">
        <PeopleProvider users={ADMIN_DIRECTORY}>
          {page === "aggregate" && <AdminDashboard />}
          {page === "users" && <UserAuditPage />}
          {page === "health" && <HealthPage />}
          {page === "feedback" && <ModerationPage />}
        </PeopleProvider>
      </main>

      <nav className="glass-bar fixed inset-x-0 bottom-0 z-30 grid h-16 grid-cols-4 border-t md:hidden">
        {NAV.map(({ id, short, icon: Icon }) => (
          <button
            key={id}
            type="button"
            onClick={() => setPage(id)}
            className={cn(
              "relative flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider",
              page === id ? "text-primary" : "text-muted-foreground",
            )}
          >
            <Icon className="size-5" />
            {short}
            {badge(id) && (
              <span className="absolute right-1/2 top-2 translate-x-4 rounded-full bg-loss px-1 font-mono text-[9px] font-bold text-primary-foreground">
                {badge(id)}
              </span>
            )}
          </button>
        ))}
      </nav>
    </div>
  );
}

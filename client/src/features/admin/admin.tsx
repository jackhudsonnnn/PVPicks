import { useState } from "react";
import {
  BarChart3,
  BookOpen,
  ExternalLink,
  Flag,
  KeyRound,
  LogOut,
  Trophy,
  Users,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Logo } from "@/components/logo";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { ChangePasswordSection } from "@/features/auth";
import type { User } from "@/features/people";
import { cn } from "@/lib/utils";
import { PERF_WINDOWS, type League, type PerfWindow } from "./dashboard-api";
import { FeedbackInbox } from "./feedback";
import { Moderation } from "./moderation";
import { PerfTab } from "./perf";
import { SportsTab, type SportsPanel } from "./sports";
import { UsersTab } from "./users";
import { useAdminView, type AdminTab } from "./view";

const TABS = [
  { id: "users", label: "Users", hint: "Players and their audits", icon: Users },
  { id: "reports", label: "Reports", hint: "Moderation and feedback", icon: Flag },
  { id: "perf", label: "Perf", hint: "API and app performance", icon: BarChart3 },
  { id: "sports", label: "Sports", hint: "Stored feed data", icon: Trophy },
] as const;

const PANELS: SportsPanel[] = ["score", "timeline", "players", "json"];

/**
 * The admin workspace. Admin accounts can't use player APIs (tables, bets, chat, friends), so they see admin tools
 * only: Users (players and their audits), Reports (moderation and feedback), Perf (API and app performance) and
 * Sports (the stored feed data). The URL holds where they are, so links and screenshots point at the exact view.
 */
export function AdminWorkspace({ user, logout }: { user: User; logout: () => Promise<void> }) {
  const [view, go] = useAdminView();
  const [password, setPassword] = useState(false);
  const setTab = (tab: AdminTab) => go({ tab }, true);

  const tabs = TABS.map(({ id, label, hint, icon: Icon }) => ({ id, label, hint, Icon }));

  return (
    <div className="min-h-dvh overflow-x-clip bg-field">
      {/* Desktop: a sidebar like the player app's. */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r bg-sidebar nav:flex">
        <div className="flex h-16 shrink-0 items-center justify-between pl-5 pr-3">
          <Logo badge={<AdminBadge />} />
          <SignOut onSignOut={() => void logout()} />
        </div>
        <nav aria-label="Admin tools" className="flex shrink-0 flex-col gap-1 px-3 py-2">
          {tabs.map(({ id, label, hint, Icon }) => {
            const active = view.tab === id;
            return (
              <button
                key={id}
                type="button"
                aria-current={active ? "page" : undefined}
                onClick={() => setTab(id)}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-semibold transition-colors",
                  active
                    ? "bg-sidebar-accent text-foreground"
                    : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
                )}
              >
                <Icon className={cn("size-4 shrink-0", active && "text-primary")} />
                <span className="min-w-0">
                  <span className="block">{label}</span>
                  <span className="block truncate text-[11px] font-normal text-muted-foreground">
                    {hint}
                  </span>
                </span>
              </button>
            );
          })}
          <div className="my-1 border-t" />
          {/* The handbook opens in a new tab; on LAN and in production only an admin session can read it. */}
          <a
            href="/docs/"
            target="_blank"
            rel="noopener"
            className="group flex items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-semibold text-muted-foreground transition-colors hover:bg-sidebar-accent/60 hover:text-foreground"
          >
            <BookOpen className="size-4 shrink-0" />
            <span className="min-w-0 flex-1">
              <span className="block">Handbook</span>
              <span className="block truncate text-[11px] font-normal text-muted-foreground">
                Architecture, rules and runbooks
              </span>
            </span>
            <ExternalLink className="size-3.5 shrink-0 opacity-0 transition-opacity group-hover:opacity-100" />
          </a>
        </nav>
        <div className="mt-auto shrink-0 border-t p-4">
          <Button variant="outline" size="sm" className="w-full" onClick={() => setPassword(true)}>
            <KeyRound /> Change password
          </Button>
          <p className="mt-2 text-[11px] leading-snug text-muted-foreground">
            Admin accounts manage the platform and can't play. Create yourself a player account
            under Users to play.
          </p>
          <p className="mt-2 truncate text-xs text-muted-foreground">
            Signed in as {user.username}
          </p>
        </div>
      </aside>

      {/* Phone: a top bar and a bottom tab bar, as in the player app. */}
      <header className="glass-bar sticky top-0 z-30 flex h-14 items-center justify-between gap-2 border-b px-3 nav:hidden">
        <Logo badge={<AdminBadge />} />
        <div className="flex shrink-0 items-center gap-1">
          <Button variant="ghost" size="icon" asChild>
            <a href="/docs/" target="_blank" rel="noopener" aria-label="Handbook" title="Handbook">
              <BookOpen />
            </a>
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Change password"
            onClick={() => setPassword(true)}
          >
            <KeyRound />
          </Button>
          <SignOut onSignOut={() => void logout()} />
        </div>
      </header>
      <nav
        aria-label="Admin tools (phone)"
        className="glass-bar fixed inset-x-0 bottom-0 z-30 grid h-16 grid-cols-4 border-t nav:hidden"
      >
        {tabs.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            aria-current={view.tab === id ? "page" : undefined}
            onClick={() => setTab(id)}
            className={cn(
              "flex flex-col items-center justify-center gap-1 text-[10px] font-semibold uppercase tracking-wider",
              view.tab === id ? "text-primary" : "text-muted-foreground",
            )}
          >
            <Icon className="size-5" />
            {label}
          </button>
        ))}
      </nav>

      <main className="px-4 pb-24 pt-6 md:px-6 nav:pb-8 nav:pl-[calc(16rem+1.5rem)]">
        <div className="mx-auto max-w-[1600px]">
          {view.tab === "users" ? (
            <UsersTab
              user={user}
              playerId={view.user ?? null}
              openPlayer={(id) => go({ tab: "users", ...(id ? { user: id } : {}) }, true)}
              openReports={() => go({ tab: "reports" }, true)}
            />
          ) : view.tab === "reports" ? (
            <ReportsTab
              user={user}
              section={view.section === "feedback" ? "feedback" : "moderation"}
              onSection={(section) => go({ tab: "reports", section }, false)}
              openPlayer={(id) => go({ tab: "users", user: id }, true)}
            />
          ) : view.tab === "perf" ? (
            <PerfTab
              user={user}
              window={
                PERF_WINDOWS.includes(view.window as PerfWindow)
                  ? (view.window as PerfWindow)
                  : "1h"
              }
              source={view.source === "client" ? "client" : "server"}
              route={view.route ?? null}
              onChange={(next) =>
                go(
                  {
                    tab: "perf",
                    window: next.window,
                    source: next.source,
                    ...(next.route ? { route: next.route } : {}),
                  },
                  (next.route ?? null) !== (view.route ?? null),
                )
              }
            />
          ) : (
            <SportsTab
              user={user}
              selection={{
                league:
                  view.league === "NBA" || view.league === "NFL" ? (view.league as League) : null,
                day: view.day ?? null,
                game: view.game ?? null,
                panel: PANELS.includes(view.panel as SportsPanel)
                  ? (view.panel as SportsPanel)
                  : "score",
                player: view.player ?? null,
              }}
              onSelect={(next, push) =>
                go(
                  {
                    tab: "sports",
                    ...(next.league ? { league: next.league } : {}),
                    ...(next.day ? { day: next.day } : {}),
                    ...(next.game ? { game: next.game } : {}),
                    ...(next.panel !== "score" ? { panel: next.panel } : {}),
                    ...(next.player ? { player: next.player } : {}),
                  },
                  push,
                )
              }
            />
          )}
        </div>
      </main>
      <Dialog open={password} onOpenChange={setPassword}>
        <DialogContent>
          <DialogTitle>Change password</DialogTitle>
          <DialogDescription className="sr-only">
            Change this admin account's password.
          </DialogDescription>
          <ChangePasswordSection username={user.username} />
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ReportsTab({
  user,
  section,
  onSection,
  openPlayer,
}: {
  user: User;
  section: "moderation" | "feedback";
  onSection: (section: "moderation" | "feedback") => void;
  openPlayer: (id: string) => void;
}) {
  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <SegmentedTabs
        size="sm"
        ariaLabel="Reports"
        tabs={[
          { id: "moderation", label: "Moderation" },
          { id: "feedback", label: "Feedback" },
        ]}
        value={section}
        onChange={onSection}
      />
      {section === "moderation" ? (
        <Moderation user={user} onOpenPlayer={openPlayer} />
      ) : (
        <FeedbackInbox user={user} onOpenPlayer={openPlayer} />
      )}
    </div>
  );
}

function AdminBadge() {
  return <span className="rounded bg-muted px-2 py-0.5 font-mono text-xs font-bold">Admin</span>;
}

function SignOut({ onSignOut }: { onSignOut: () => void }) {
  return (
    <Button variant="ghost" size="icon" aria-label="Sign out" title="Sign out" onClick={onSignOut}>
      <LogOut />
    </Button>
  );
}

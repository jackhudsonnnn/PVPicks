import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import {
  AlertTriangle,
  Bell,
  BellRing,
  MessageCircle,
  Palette,
  Armchair,
  UserPlus,
  Send,
  Ticket,
  UserRound,
  ShieldCheck,
} from "lucide-react";
import { SafetySettings } from "@/features/settings/components/safety-settings";
import { PageHeader } from "@/components/page-header";
import { AccountSettings } from "@/features/settings/components/account-settings";
import { DisplaySettings } from "@/features/settings/components/display-settings";
import { TableInviteSettings } from "@/features/settings/components/table-invite-settings";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { SettingRow, SettingsSection } from "@/components/settings";
import { Button } from "@/components/ui/button";
import { ScrollContainer } from "@/components/scroll-container";
import { Switch } from "@/components/ui/switch";
import { myTables, useAppStore, useMe } from "@/features/store";
import { useNotifications } from "@/features/notifications/notifications";
import type { TicketScope } from "@/features/notifications/events";

export const Route = createFileRoute("/preferences")({
  head: () => ({
    meta: [
      { title: "Preferences | P2Picks" },
      {
        name: "description",
        content: "Your P2Picks notifications, table invites, profile, safety and display settings.",
      },
      { property: "og:title", content: "Preferences | P2Picks" },
      {
        property: "og:description",
        content: "Your P2Picks notifications, table invites, profile and display settings.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: PreferencesPage,
});

const TABS = [
  { id: "notifications", label: "Alerts", icon: Bell },
  { id: "tables", label: "Tables", icon: Armchair },
  { id: "account", label: "Account", icon: UserRound },
  { id: "safety", label: "Safety", icon: ShieldCheck },
  { id: "display", label: "Display", icon: Palette },
] as const;

type TabId = (typeof TABS)[number]["id"];

function PreferencesPage() {
  const [tab, setTab] = useState<TabId>("notifications");

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-6 md:px-8 md:py-10">
      <div className="[&_h1]:text-3xl sm:[&_h1]:text-4xl md:[&_h1]:text-5xl">
        <PageHeader eyebrow="Settings" title="Preferences" action={
          <div className="md:hidden">
            <SegmentedTabs dropdownOnly tabs={TABS} value={tab} onChange={setTab} ariaLabel="Preference sections" />
          </div>
        } />
      </div>
      <div className="hidden md:block">
        <SegmentedTabs tabs={TABS} value={tab} onChange={setTab} ariaLabel="Preference sections" />
      </div>
      {tab === "notifications" && <NotificationSettings />}
      {tab === "tables" && <TableInviteSettings />}
      {tab === "account" && <AccountSettings />}
      {tab === "safety" && <SafetySettings />}
      {tab === "display" && <DisplaySettings />}
    </div>
  );
}

function NotificationSettings() {
  const me = useMe();
  const { prefs, update, toggleTableMute, notify } = useNotifications();
  const { state } = useAppStore();
  const off = !prefs.enabled;

  function sendTest() {
    const bet =
      state.bets.find((b) => b.participants.some((p) => p.userId === me)) ?? state.bets[0];
    if (bet)
      notify({
        kind: "ticket",
        bet: { ...bet, status: "pending" },
        from: "active",
        to: "pending",
      });
  }

  return (
    <div className="space-y-4">
      <SettingsSection
        title="Notifications"
        description="Pop-ups in the top-right corner while you use the app."
        action={
          <Button size="sm" variant="outline" onClick={sendTest} disabled={off}>
            <Send /> Test
          </Button>
        }
      >
        <SettingRow
          icon={BellRing}
          label="All notifications"
          description="Turn off to silence every pop-up."
          htmlFor="n-all"
          control={
            <Switch
              id="n-all"
              checked={prefs.enabled}
              onCheckedChange={(v) => update({ enabled: v })}
            />
          }
        />
        <SettingRow
          icon={AlertTriangle}
          label="Errors"
          description="When something you tried didn't go through, like not enough points."
          htmlFor="n-err"
          disabled={off}
          control={
            <Switch
              id="n-err"
              disabled={off}
              checked={prefs.errors}
              onCheckedChange={(v) => update({ errors: v })}
            />
          }
        />
        <SettingRow
          icon={Ticket}
          label="Ticket updates"
          description="New tickets, picks locking, wins, losses and washes."
          htmlFor="n-tix"
          disabled={off}
          control={
            <Switch
              id="n-tix"
              disabled={off}
              checked={prefs.ticketStatus}
              onCheckedChange={(v) => update({ ticketStatus: v })}
            />
          }
        >
          {prefs.ticketStatus && !off && (
            <SegmentedTabs<TicketScope>
              size="sm"
              ariaLabel="Which tickets"
              tabs={[
                { id: "mine", label: "Tickets I'm in" },
                { id: "all", label: "Every ticket" },
              ]}
              value={prefs.ticketScope}
              onChange={(v) => update({ ticketScope: v })}
            />
          )}
        </SettingRow>
        <SettingRow
          icon={UserPlus}
          label="Table invites"
          description="When someone adds you to a table or asks you to join one."
          htmlFor="n-inv"
          disabled={off}
          control={
            <Switch
              id="n-inv"
              disabled={off}
              checked={prefs.invites}
              onCheckedChange={(v) => update({ invites: v })}
            />
          }
        />
        <SettingRow
          icon={MessageCircle}
          label="New chats"
          description="Messages from tablemates. Skipped for the table you're looking at."
          htmlFor="n-chat"
          disabled={off}
          control={
            <Switch
              id="n-chat"
              disabled={off}
              checked={prefs.chats}
              onCheckedChange={(v) => update({ chats: v })}
            />
          }
        >
          {prefs.chats && !off && (
            <ScrollContainer isVisible={false} className="max-h-64">
              <ul className="grid gap-1.5">
                {myTables(state).map((t) => {
                  const on = !prefs.mutedTableIds.includes(t.id);
                  return (
                    <li
                      key={t.id}
                      className="flex items-center gap-3 rounded-lg border bg-sidebar px-3 py-2"
                    >
                      <span className="text-base">{t.emoji}</span>
                      <label
                        htmlFor={`mute-${t.id}`}
                        className="min-w-0 flex-1 break-words text-sm"
                      >
                        {t.name}
                      </label>
                      <span className="font-mono text-[10px] uppercase tracking-wider text-muted-foreground">
                        {on ? "On" : "Muted"}
                      </span>
                      <Switch
                        id={`mute-${t.id}`}
                        checked={on}
                        onCheckedChange={() => toggleTableMute(t.id)}
                      />
                    </li>
                  );
                })}
              </ul>
            </ScrollContainer>
          )}
        </SettingRow>
      </SettingsSection>
    </div>
  );
}

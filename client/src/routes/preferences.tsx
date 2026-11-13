import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";
import { Armchair, Palette, ShieldCheck, UserRound } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { AccountSettings } from "@/features/settings/components/account-settings";
import { DisplaySettings } from "@/features/settings/components/display-settings";
import { SafetySettings } from "@/features/settings/components/safety-settings";
import { TableInviteSettings } from "@/features/settings/components/table-invite-settings";

export const Route = createFileRoute("/preferences")({
  head: () => ({
    meta: [
      { title: "Preferences | P2Picks" },
      {
        name: "description",
        content: "Your P2Picks table invites, profile, safety and display settings.",
      },
      { property: "og:title", content: "Preferences | P2Picks" },
      {
        property: "og:description",
        content: "Your P2Picks table invites, profile, safety and display settings.",
      },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary" },
    ],
  }),
  component: PreferencesPage,
});

const TABS = [
  { id: "tables", label: "Tables", icon: Armchair },
  { id: "account", label: "Account", icon: UserRound },
  { id: "safety", label: "Safety", icon: ShieldCheck },
  { id: "display", label: "Display", icon: Palette },
] as const;

type TabId = (typeof TABS)[number]["id"];

function PreferencesPage() {
  const [tab, setTab] = useState<TabId>("tables");
  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-6 md:px-8 md:py-10">
      <div className="[&_h1]:text-3xl sm:[&_h1]:text-4xl md:[&_h1]:text-5xl">
        <PageHeader
          eyebrow="Settings"
          title="Preferences"
          action={
            <div className="md:hidden">
              <SegmentedTabs
                dropdownOnly
                tabs={TABS}
                value={tab}
                onChange={setTab}
                ariaLabel="Preference sections"
              />
            </div>
          }
        />
      </div>
      <div className="hidden md:block">
        <SegmentedTabs tabs={TABS} value={tab} onChange={setTab} ariaLabel="Preference sections" />
      </div>
      {tab === "tables" && <TableInviteSettings />}
      {tab === "account" && <AccountSettings />}
      {tab === "safety" && <SafetySettings />}
      {tab === "display" && <DisplaySettings />}
    </div>
  );
}

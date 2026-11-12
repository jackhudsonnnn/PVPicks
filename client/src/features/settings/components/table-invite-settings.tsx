import { Heart, UserCheck } from "lucide-react";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { SettingRow, SettingsSection } from "@/components/settings";
import { invitePrefsOf, useAppStore, useMe } from "@/features/store";
import { INVITE_MODES, type InviteMode, type InvitePrefs } from "@/features/social/invites";
import { useSettings } from "../settings-provider";

const ROWS = [
  {
    key: "friends",
    icon: Heart,
    label: "Friends",
    description: "Hosts you follow who follow you back.",
  },
  {
    key: "following",
    icon: UserCheck,
    label: "People you follow",
    description: "Hosts you follow who don't follow you back.",
  },
] as const;

/** Who can add you to their tables, and whether you get a request first. */
export function TableInviteSettings() {
  const me = useMe();
  const { state } = useAppStore();
  const { saveInvitePrefs } = useSettings();
  const prefs = invitePrefsOf(state, me);

  return (
    <div className="space-y-4">
      <SettingsSection
        title="Adding you to tables"
        description="Only friends and people you follow can add you. Requests show in yellow under Your tables."
      >
        {ROWS.map((r) => {
          const mode = prefs[r.key];
          return (
            <SettingRow key={r.key} icon={r.icon} label={r.label} description={r.description}>
              <SegmentedTabs<InviteMode>
                size="sm"
                ariaLabel={`${r.label}: who can add you`}
                tabs={INVITE_MODES.map((m) => ({ id: m.id, label: m.label }))}
                value={mode}
                onChange={(v) => saveInvitePrefs({ ...prefs, [r.key]: v } as InvitePrefs)}
              />
              <p className="mt-2 text-xs text-muted-foreground">
                {INVITE_MODES.find((m) => m.id === mode)!.description}
              </p>
            </SettingRow>
          );
        })}
      </SettingsSection>
    </div>
  );
}

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Heart, Palette, ShieldAlert, UserCheck, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SegmentedTabs } from "@/components/segmented-tabs";
import { SettingRow, SettingsSection } from "@/components/settings";
import { HUE_BG } from "@/features/social/components/hue";
import { INVITE_MODES } from "@/features/social/invites";
import { HUES, normalizeUsername, usernameError, type Hue } from "@/features/settings/profile";
import { cn } from "@/lib/utils";
import { backendErrorMessage, type BackendUser } from "./backend-api";
import { ActionStatus } from "./backend-action-status";
import { usePreferencesActions } from "./preferences-actions";
import {
  getDebt,
  getDebtCap,
  getInvitePreferences,
  type InviteMode,
  type InvitePreferences,
} from "./preferences-api";
import { userKey } from "./query-keys";
import { formatPoints } from "./settlement-api";
import { parseCap } from "./management-rules";

const SESSION_KEY = ["backend-session"];

const INVITE_ROWS = [
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

/** Preferences: who can add you to tables, and your account (username, color, debt cap). */
export function BackendPreferences({ user, expire }: { user: BackendUser; expire: () => void }) {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<"tables" | "account">("tables");
  const actions = usePreferencesActions(
    user.id,
    async () => {
      await queryClient.invalidateQueries({ queryKey: userKey(user.id) });
      await queryClient.invalidateQueries({ queryKey: SESSION_KEY });
    },
    expire,
  );
  const disabled = actions.busy || !!actions.pending || !!actions.storageError;
  return (
    <div className="space-y-6">
      <h1 className="font-display text-3xl font-bold uppercase">Preferences</h1>
      <SegmentedTabs
        ariaLabel="Preference sections"
        tabs={[
          { id: "tables", label: "Tables" },
          { id: "account", label: "Account" },
        ]}
        value={tab}
        onChange={setTab}
      />
      <ActionStatus
        actions={actions}
        pendingText="A preference change may not have been saved."
        checkText="Check your preferences first."
      />
      {tab === "tables" ? (
        <InviteSettings user={user} disabled={disabled} actions={actions} />
      ) : (
        // Keyed by username so the form resets to the saved name after a rename.
        <AccountSettings key={user.username} user={user} disabled={disabled} actions={actions} />
      )}
    </div>
  );
}

function InviteSettings({
  user,
  disabled,
  actions,
}: {
  user: BackendUser;
  disabled: boolean;
  actions: ReturnType<typeof usePreferencesActions>;
}) {
  const query = useQuery({
    queryKey: userKey(user.id, "invite-preferences"),
    queryFn: ({ signal }) => getInvitePreferences(signal),
    retry: false,
  });
  if (query.isPending) return <p>Loading…</p>;
  if (query.isError) return <p role="alert">{backendErrorMessage(query.error)}</p>;
  const preferences: InvitePreferences = query.data;
  return (
    <SettingsSection
      title="Adding you to tables"
      description="Only friends and people you follow can add you. Requests appear as invitations on Home."
    >
      {INVITE_ROWS.map((row) => {
        const mode = preferences[row.key];
        return (
          <SettingRow key={row.key} icon={row.icon} label={row.label} description={row.description}>
            <SegmentedTabs<InviteMode>
              size="sm"
              ariaLabel={`${row.label}: who can add you`}
              tabs={INVITE_MODES.map((m) => ({ id: m.id, label: m.label, disabled }))}
              value={mode}
              onChange={(value) =>
                actions.start({ kind: "invites", ...preferences, [row.key]: value })
              }
            />
            <p className="mt-2 text-xs text-muted-foreground">
              {INVITE_MODES.find((m) => m.id === mode)?.description}
            </p>
          </SettingRow>
        );
      })}
    </SettingsSection>
  );
}

function AccountSettings({
  user,
  disabled,
  actions,
}: {
  user: BackendUser;
  disabled: boolean;
  actions: ReturnType<typeof usePreferencesActions>;
}) {
  const [username, setUsername] = useState(user.username);
  const [problem, setProblem] = useState<string | null>(null);
  const changed = normalizeUsername(username) !== user.username;

  function saveUsername(event: React.FormEvent) {
    event.preventDefault();
    const invalid = usernameError(username, []);
    setProblem(invalid ?? null);
    if (invalid || !changed) return;
    actions.start({ kind: "profile", username: normalizeUsername(username) });
  }

  return (
    <div className="space-y-6">
      <SettingsSection title="Profile">
        <SettingRow
          icon={UserRound}
          label="Username"
          description="3–15 lowercase letters, numbers or underscores. It is also how you sign in."
        >
          <form className="flex flex-wrap gap-2" onSubmit={saveUsername}>
            <Input
              aria-label="Username"
              value={username}
              disabled={disabled}
              onChange={(event) => setUsername(event.target.value)}
              className="max-w-56"
            />
            <Button type="submit" size="sm" disabled={disabled || !changed}>
              Save username
            </Button>
          </form>
          {problem && <p role="alert">{problem}</p>}
        </SettingRow>
        <SettingRow icon={Palette} label="Color" description="Your avatar color.">
          <div role="radiogroup" aria-label="Avatar color" className="flex gap-2">
            {HUES.map((hue: Hue) => (
              <button
                key={hue}
                type="button"
                role="radio"
                aria-checked={user.hue === hue}
                aria-label={`Color ${hue}`}
                disabled={disabled}
                onClick={() => user.hue !== hue && actions.start({ kind: "profile", hue })}
                className={cn(
                  "size-8 rounded-full border-2",
                  HUE_BG[hue],
                  user.hue === hue ? "border-foreground" : "border-transparent",
                )}
              />
            ))}
          </div>
        </SettingRow>
      </SettingsSection>
      <DebtCapSettings user={user} disabled={disabled} actions={actions} />
    </div>
  );
}

function DebtCapSettings({
  user,
  disabled,
  actions,
}: {
  user: BackendUser;
  disabled: boolean;
  actions: ReturnType<typeof usePreferencesActions>;
}) {
  const cap = useQuery({
    queryKey: userKey(user.id, "debt-cap"),
    queryFn: ({ signal }) => getDebtCap(signal),
    retry: false,
  });
  const debt = useQuery({
    queryKey: userKey(user.id, "debt"),
    queryFn: ({ signal }) => getDebt(signal),
    retry: false,
  });
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  if (cap.isPending || debt.isPending) return <p>Loading debt cap…</p>;
  if (cap.isError || debt.isError)
    return <p role="alert">{backendErrorMessage(cap.error ?? debt.error)}</p>;
  const locked = debt.data > 0;
  const on = cap.data !== null;

  function save(event: React.FormEvent) {
    event.preventDefault();
    const parsed = parseCap(text);
    if ("problem" in parsed) {
      setProblem(parsed.problem);
      return;
    }
    setProblem(null);
    actions.start({ kind: "debt-cap", capCents: parsed.cents });
  }

  return (
    <SettingsSection
      title="Debt cap"
      description="Stops you joining or proposing bets that could push your debt past the cap. You can only change it while you owe nothing."
    >
      <SettingRow
        icon={ShieldAlert}
        label="Current debt"
        description="What you owe across tables and open settle-ups."
      >
        <p className="font-semibold">{formatPoints(BigInt(debt.data))} points</p>
      </SettingRow>
      <SettingRow
        icon={ShieldAlert}
        label="Cap"
        description={on ? `${formatPoints(BigInt(cap.data!))} points` : "Off"}
      >
        <div className="space-y-2">
          <span className="flex items-center gap-2">
            <Switch
              id="debt-cap-on"
              checked={on}
              disabled={disabled || locked || !on}
              onCheckedChange={(checked) =>
                !checked && actions.start({ kind: "debt-cap", capCents: null })
              }
            />
            <Label htmlFor="debt-cap-on">{on ? "Cap on" : "No cap"}</Label>
          </span>
          <form className="flex flex-wrap items-center gap-2" onSubmit={save}>
            <Input
              aria-label="Debt cap in points"
              inputMode="numeric"
              placeholder="Points"
              value={text}
              disabled={disabled || locked}
              onChange={(event) => setText(event.target.value)}
              className="max-w-32"
            />
            <Button type="submit" size="sm" disabled={disabled || locked}>
              {on ? "Change cap" : "Set cap"}
            </Button>
          </form>
          {locked && (
            <p className="text-xs text-muted-foreground">
              Settle what you owe before changing your cap.
            </p>
          )}
          {problem && <p role="alert">{problem}</p>}
        </div>
      </SettingRow>
    </SettingsSection>
  );
}

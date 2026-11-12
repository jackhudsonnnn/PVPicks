import { useAuth } from "@/features/auth/auth-provider";
import { accountLabel } from "@/features/auth/demo-accounts";
import { accountStorageKeys } from "@/features/auth/storage-keys";
import { useState } from "react";
import { AlertTriangle, AtSign, LogIn, Palette, Trash2 } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { formatPts } from "@/features/bets/ledger";
import { unconfirmedTotal } from "@/features/table/settlements";
import { cn } from "@/lib/utils";
import { removeKeys } from "@/lib/storage";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SettingRow, SettingsSection } from "@/components/settings";
import { useAppStore, useMe } from "@/features/store";
import { UserAvatar } from "@/features/social/components/user-avatar";
import { HUE_BG } from "@/features/social/components/hue";
import { HUES, USERNAME_MAX, normalizeUsername, usernameError, type Hue } from "../profile";
import { useMyProfile, useSettings } from "../settings-provider";

export function AccountSettings() {
  const me = useMe();
  const { state } = useAppStore();
  const { saveProfile } = useSettings();
  const { account, signOut } = useAuth();
  const saved = useMyProfile();
  const [username, setUsername] = useState(saved.username);
  const [hue, setHue] = useState<Hue>(saved.hue);
  const [justSaved, setJustSaved] = useState(false);

  const taken = state.users.filter((u) => u.id !== me).map((u) => u.username);
  const error = usernameError(username, taken);
  const clean = { username: normalizeUsername(username), hue };
  const dirty = clean.username !== saved.username || clean.hue !== saved.hue;
  const canSave = dirty && !error;

  return (
    <div className="space-y-4">
      <SettingsSection
        title="Profile"
        description="How tablemates see you on seats, tickets and chat."
      >
        <div className="flex items-center gap-4 px-4 py-4 sm:px-5">
          <UserAvatar userId={me} size="lg" className="ring-surface" />
          <div className="min-w-0">
            <p className="break-words font-display text-2xl font-black uppercase leading-none">
              {saved.username}
            </p>
          </div>
        </div>
        <Field icon={AtSign} id="acc-username" label="Username" error={error}>
          <Input
            id="acc-username"
            value={username}
            maxLength={USERNAME_MAX}
            autoCapitalize="none"
            spellCheck={false}
            onChange={(e) => {
              setUsername(e.target.value);
              setJustSaved(false);
            }}
            className="h-10 bg-sidebar font-mono"
          />
        </Field>
        <SettingRow icon={Palette} label="Avatar color">
          <div className="flex gap-2" role="radiogroup" aria-label="Avatar color">
            {HUES.map((h) => (
              <button
                key={h}
                type="button"
                role="radio"
                aria-checked={hue === h}
                aria-label={`Color ${h}`}
                onClick={() => {
                  setHue(h);
                  setJustSaved(false);
                }}
                className={cn(
                  "size-9 rounded-full ring-offset-2 ring-offset-surface transition-shadow",
                  HUE_BG[h],
                  hue === h && "ring-2 ring-foreground",
                )}
              />
            ))}
          </div>
        </SettingRow>
        <div className="flex items-center gap-3 px-4 py-3.5 sm:px-5">
          <Button
            size="sm"
            disabled={!canSave}
            onClick={() => {
              saveProfile(clean);
              setUsername(clean.username);
              setJustSaved(true);
            }}
          >
            Save profile
          </Button>
          {justSaved && !dirty && <span className="text-xs text-muted-foreground">Saved</span>}
        </div>
      </SettingsSection>

      <SettingsSection
        title="Sign-in"
        description="Demo accounts for testing. Real accounts come later."
      >
        <SettingRow
          icon={LogIn}
          label={`Signed in as ${accountLabel(account ?? undefined)}`}
          description="Sign out to switch accounts or create a new one."
          control={
            <Button size="sm" variant="outline" onClick={() => signOut()}>
              Sign out
            </Button>
          }
        />
      </SettingsSection>

      <DangerZone />
    </div>
  );
}

function DangerZone() {
  const me = useMe();
  const { state, actions } = useAppStore();
  const { account, forgetAccount } = useAuth();
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const myUsername = useMyProfile().username;
  const owed = unconfirmedTotal(me, state.bets, state.messages);
  const live = state.bets.filter(
    (b) => b.status === "active" && b.participants.some((p) => p.userId === me),
  ).length;

  function remove() {
    actions.deleteAccount();
    // Display settings stay with the device; everything saved for this account goes.
    removeKeys(...Object.values(accountStorageKeys(account ?? "none")));
    forgetAccount(
      account?.startsWith("user:")
        ? "Your account was deleted."
        : "Your account was deleted. (Demo: signing in again starts this sample account fresh.)",
    );
  }

  return (
    <section className="space-y-3 rounded-2xl border border-loss/50 bg-loss/5 p-4 sm:p-5">
      <div>
        <h2 className="flex items-center gap-2 font-display text-2xl font-extrabold uppercase text-loss">
          <AlertTriangle className="size-5" /> Danger zone
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Delete your account and everything personal in it. This can't be undone.
        </p>
      </div>
      <Button variant="destructive" size="sm" onClick={() => setOpen(true)}>
        <Trash2 /> Delete account
      </Button>

      <AlertDialog
        open={open}
        onOpenChange={(o) => {
          setOpen(o);
          if (!o) setTyped("");
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle className="font-display text-3xl font-black uppercase">
              Delete your account?
            </AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="space-y-3 text-left text-sm text-muted-foreground">
                <ul className="list-disc space-y-1 pl-5">
                  <li>Your profile, chats, reactions, follows and blocks are erased.</li>
                  <li>
                    You leave every table. If you're the only host, the next member takes over.
                  </li>
                  <li>
                    Past tickets and settle-ups stay so everyone's totals still add up, shown as
                    “Deleted user”.
                  </li>
                  {live > 0 && (
                    <li>
                      You'll drop out of {live} open ticket{live === 1 ? "" : "s"} you picked on.
                    </li>
                  )}
                </ul>
                {owed !== 0 && (
                  <p className="rounded-lg border border-loss/50 bg-loss/10 px-3 py-2 text-foreground">
                    You still have{" "}
                    <span className="font-semibold">
                      {owed > 0 ? "+" : "-"}${formatPts(Math.abs(owed))}
                    </span>{" "}
                    not settled. {owed > 0 ? "Collect it" : "Pay it"} first: once you're gone,
                    nobody can confirm it in the app.
                  </p>
                )}
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-1.5">
            <label htmlFor="del-confirm" className="text-sm font-semibold">
              Type <span className="font-mono">{myUsername}</span> to confirm
            </label>
            <Input
              id="del-confirm"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="off"
              autoCapitalize="none"
              className="h-10 bg-sidebar font-mono"
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep my account</AlertDialogCancel>
            <AlertDialogAction
              disabled={typed.trim().toLowerCase() !== myUsername}
              onClick={remove}
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
            >
              Delete forever
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}

function Field({
  icon: Icon,
  id,
  label,
  error,
  children,
}: {
  icon: typeof AtSign;
  id: string;
  label: string;
  error?: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5 px-4 py-3.5 sm:px-5">
      <label htmlFor={id} className="flex items-center gap-2 text-sm font-semibold">
        <Icon className="size-4 text-primary" /> {label}
      </label>
      {children}
      {error && <p className="text-xs text-loss">{error}</p>}
    </div>
  );
}
